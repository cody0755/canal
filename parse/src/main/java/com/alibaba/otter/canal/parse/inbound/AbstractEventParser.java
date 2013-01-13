package com.alibaba.otter.canal.parse.inbound;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.math.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.alibaba.erosa.protocol.protobuf.ErosaEntry;
import com.alibaba.otter.canal.common.AbstractCanalLifeCycle;
import com.alibaba.otter.canal.common.alarm.CanalAlarmHandler;
import com.alibaba.otter.canal.parse.CanalEventParser;
import com.alibaba.otter.canal.parse.exception.CanalParseException;
import com.alibaba.otter.canal.parse.inbound.EventTransactionBuffer.TransactionFlushCallback;
import com.alibaba.otter.canal.parse.index.CanalLogPositionManager;
import com.alibaba.otter.canal.parse.support.AuthenticationInfo;
import com.alibaba.otter.canal.protocol.position.EntryPosition;
import com.alibaba.otter.canal.protocol.position.LogIdentity;
import com.alibaba.otter.canal.protocol.position.LogPosition;
import com.alibaba.otter.canal.sink.CanalEventSink;
import com.alibaba.otter.canal.sink.exception.CanalSinkException;

/**
 * 抽象的EventParser, 最大化共用mysql/oracle版本的实现
 * 
 * @author: yuanzu Date: 12-9-20 Time: 下午2:55
 */
public abstract class AbstractEventParser extends AbstractCanalLifeCycle implements CanalEventParser {

    protected final Logger                           logger             = LoggerFactory.getLogger(this.getClass());

    protected CanalLogPositionManager                logPositionManager = null;
    protected CanalEventSink<List<ErosaEntry.Entry>> eventSink          = null;

    private CanalAlarmHandler                        alarmHandler       = null;

    // 统计参数
    protected AtomicBoolean                          profilingEnabled   = new AtomicBoolean(false);                // profile开关参数
    protected AtomicLong                             receivedEventCount = new AtomicLong();
    protected AtomicLong                             parsedEventCount   = new AtomicLong();
    protected AtomicLong                             consumedEventCount = new AtomicLong();
    protected long                                   parsingInterval    = -1;
    protected long                                   processingInterval = -1;

    // 认证信息
    protected volatile AuthenticationInfo            runningInfo;
    protected String                                 destination;

    // binLogParser
    protected BinlogParser                           binlogParser       = null;

    private Thread                                   parseThread        = null;

    private Thread.UncaughtExceptionHandler          handler            = new Thread.UncaughtExceptionHandler() {

                                                                            public void uncaughtException(Thread t,
                                                                                                          Throwable e) {
                                                                                logger.error(
                                                                                             "parse events has an error",
                                                                                             e);
                                                                            }
                                                                        };

    private EventTransactionBuffer                   transactionBuffer;
    private int                                      transactionSize    = 1024;

    protected abstract BinlogParser buildParser();

    protected abstract ErosaConnection buildErosaConnection();

    protected abstract EntryPosition findStartPosition(ErosaConnection connection) throws IOException;

    protected void preDump(ErosaConnection connection) {
    }

    protected void afterDump(ErosaConnection connection) {
    }

    public void sendAlarm(String destination, String msg) {
        if (this.alarmHandler != null) {
            this.alarmHandler.sendAlarm(destination, msg);
        }
    }

    public AbstractEventParser(){
        // 初始化一下
        transactionBuffer = new EventTransactionBuffer(new TransactionFlushCallback() {

            public void flush(List<ErosaEntry.Entry> transaction) throws InterruptedException {
                boolean successed = consumeTheEventAndProfilingIfNecessary(transaction);
                if (!running) {
                    return;
                }

                if (!successed) {
                    throw new CanalParseException("consume failed!");
                }

                LogPosition position = buildLastTranasctionPosition(transaction);
                if (position != null) { // 可能position为空
                    logPositionManager.persistLogPosition(AbstractEventParser.this.destination, position);
                }
            }
        });
    }

    public void start() {
        super.start();
        MDC.put("destination", destination);
        // 配置transaction buffer
        // 初始化缓冲队列
        transactionBuffer.setBufferSize(transactionSize);// 设置buffer大小
        transactionBuffer.start();
        // 1. 构造bin log parser
        buildParser();// 初始化一下BinLogParser
        binlogParser.start();
        // 启动工作线程
        parseThread = new Thread(new Runnable() {

            public void run() {
                MDC.put("destination", String.valueOf(destination));
                ErosaConnection erosaConnection = null;
                while (running) {
                    try {

                        // 开始执行replication
                        // 1. 构造Erosa连接
                        erosaConnection = buildErosaConnection();
                        erosaConnection.connect();// 链接
                        // 2. 获取最后的位置信息
                        final EntryPosition startPosition = findStartPosition(erosaConnection);
                        if (startPosition == null) {
                            throw new CanalParseException("can't find start position for {} " + destination);
                        }
                        // 重新链接，因为在找position过程中可能有状态，需要断开后重建
                        erosaConnection.reconnect();
                        // 3. 执行dump前的准备工作
                        preDump(erosaConnection);

                        final SinkFunction sinkHandler = new SinkFunction() {

                            private LogPosition lastPosition;

                            public boolean sink(byte[] data) {
                                try {
                                    List<ErosaEntry.Entry> entries = parseAndProfilingIfNecessary(data);

                                    if (!running) {
                                        return false;
                                    }

                                    transactionBuffer.add(entries);

                                    // 记录一下对应的positions
                                    this.lastPosition = buildLastPosition(entries);
                                    return running;
                                } catch (Exception e) {
                                    processError(e, this.lastPosition, startPosition.getJournalName(),
                                                 startPosition.getPosition());
                                    // 走到这一步，说明出错了
                                    return false;
                                }
                            }

                        };

                        // 4. 开始dump数据
                        if (StringUtils.isEmpty(startPosition.getJournalName()) && startPosition.getTimestamp() != null) {
                            erosaConnection.dump(startPosition.getTimestamp(), sinkHandler);
                        } else {
                            erosaConnection.dump(startPosition.getJournalName(), startPosition.getPosition(),
                                                 sinkHandler);
                        }

                    } catch (Throwable e) {
                        if (!running) {
                            if (!(e.getCause() instanceof java.nio.channels.ClosedByInterruptException)) {
                                throw new CanalParseException(String.format("dump address %s has an error, retrying. ",
                                                                            runningInfo.getAddress().toString()), e);
                            }
                        } else {
                            logger.error(String.format("dump address %s has an error, retrying. caused by ",
                                                       runningInfo.getAddress().toString()), e);
                            sendAlarm(destination, ExceptionUtils.getFullStackTrace(e));
                        }
                    } finally {
                        // 关闭一下链接
                        afterDump(erosaConnection);
                        try {
                            if (erosaConnection != null) {
                                erosaConnection.disconnect();
                            }
                        } catch (IOException e1) {
                            if (!running) {
                                throw new CanalParseException(
                                                              String.format(
                                                                            "disconnect address %s has an error, retrying. ",
                                                                            runningInfo.getAddress().toString()), e1);
                            } else {
                                logger.error("disconnect address {} has an error, retrying., caused by ",
                                             runningInfo.getAddress().toString(), e1);
                            }
                        }
                    }
                    // 出异常了，退出sink消费，释放一下状态
                    eventSink.interrupt();
                    transactionBuffer.reset();// 重置一下缓冲队列，重新记录数据

                    if (running) {
                        // sleep一段时间再进行重试
                        try {
                            Thread.sleep(10000 + RandomUtils.nextInt(10000));
                        } catch (InterruptedException e) {
                        }
                    }
                }
                MDC.remove("destination");
            }
        });

        parseThread.setUncaughtExceptionHandler(handler);
        parseThread.setName(String.format("destination = %s , AbstractEventParser", destination));
        parseThread.start();
    }

    public void stop() {
        super.stop();

        parseThread.interrupt(); // 尝试中断
        eventSink.interrupt();
        try {
            parseThread.join();// 等待其结束
        } catch (InterruptedException e) {
            // ignore
        }

        if (binlogParser.isStart()) {
            binlogParser.stop();
        }
        if (transactionBuffer.isStart()) {
            transactionBuffer.stop();
        }
    }

    protected boolean consumeTheEventAndProfilingIfNecessary(List<ErosaEntry.Entry> entrys) throws CanalSinkException,
                                                                                           InterruptedException {
        long startTs = -1;
        boolean enabled = getProfilingEnabled();
        if (enabled) {
            startTs = System.currentTimeMillis();
        }

        boolean result = eventSink.sink(entrys, (runningInfo == null) ? null : runningInfo.getAddress(), destination);

        if (enabled) {
            this.processingInterval = System.currentTimeMillis() - startTs;
        }

        if (consumedEventCount.incrementAndGet() < 0) {
            consumedEventCount.set(0);
        }

        return result;
    }

    public Boolean getProfilingEnabled() {
        return profilingEnabled.get();
    }

    protected LogPosition buildLastTranasctionPosition(List<ErosaEntry.Entry> entries) { // 初始化一下
        for (int i = entries.size() - 1; i > 0; i--) {
            ErosaEntry.Entry entry = entries.get(i);
            if (entry.getEntryType() == ErosaEntry.EntryType.TRANSACTIONEND) {// 尽量记录一个事务做为position
                LogPosition logPosition = new LogPosition();
                EntryPosition position = new EntryPosition();
                position.setJournalName(entry.getHeader().getLogfilename());
                position.setPosition(entry.getHeader().getLogfileoffset());
                position.setTimestamp(entry.getHeader().getExecutetime());
                logPosition.setPostion(position);

                LogIdentity identity = new LogIdentity(runningInfo.getAddress(), -1L);
                logPosition.setIdentity(identity);
                return logPosition;
            }
        }

        return null;
    }

    protected LogPosition buildLastPosition(List<ErosaEntry.Entry> entries) { // 初始化一下
        ErosaEntry.Entry entry = entries.get(entries.size() - 1);
        LogPosition logPosition = new LogPosition();
        EntryPosition position = new EntryPosition();
        position.setJournalName(entry.getHeader().getLogfilename());
        position.setPosition(entry.getHeader().getLogfileoffset());
        position.setTimestamp(entry.getHeader().getExecutetime());
        logPosition.setPostion(position);

        LogIdentity identity = new LogIdentity(runningInfo.getAddress(), -1L);
        logPosition.setIdentity(identity);
        return logPosition;
    }

    public Long getParsedEventCount() {
        return parsedEventCount.get();
    }

    public Long getConsumedEventCount() {
        return consumedEventCount.get();
    }

    public void setProfilingEnabled(boolean profilingEnabled) {
        this.profilingEnabled = new AtomicBoolean(profilingEnabled);
    }

    public long getParsingInterval() {
        return parsingInterval;
    }

    public long getProcessingInterval() {
        return processingInterval;
    }

    public void setEventSink(CanalEventSink<List<ErosaEntry.Entry>> eventSink) {
        this.eventSink = eventSink;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public void setBinlogParser(BinlogParser binlogParser) {
        this.binlogParser = binlogParser;
    }

    public BinlogParser getBinlogParser() {
        return binlogParser;
    }

    protected List<ErosaEntry.Entry> parseAndProfilingIfNecessary(byte[] body) throws Exception {
        long startTs = -1;
        boolean enabled = getProfilingEnabled();
        if (enabled) {
            startTs = System.currentTimeMillis();
        }
        List<ErosaEntry.Entry> event = binlogParser.parse(body);
        if (enabled) {
            this.parsingInterval = System.currentTimeMillis() - startTs;
        }

        if (parsedEventCount.incrementAndGet() < 0) {
            parsedEventCount.set(0);
        }
        return event;
    }

    protected void processError(Exception e, LogPosition lastPosition, String startBinlogFile, long startPosition) {
        if (lastPosition != null) {
            logger.warn(String.format("ERROR ## parse this event has an error , last position : [%s]",
                                      lastPosition.getPostion()), e);
        } else {
            logger.warn(String.format("ERROR ## parse this event has an error , last position : [%s,%s]",
                                      startBinlogFile, startPosition), e);
        }
    }

    public void setAlarmHandler(CanalAlarmHandler alarmHandler) {
        this.alarmHandler = alarmHandler;
    }

    public CanalAlarmHandler getAlarmHandler() {
        return this.alarmHandler;
    }

    public void setLogPositionManager(CanalLogPositionManager logPositionManager) {
        this.logPositionManager = logPositionManager;
    }

    public void setTransactionSize(int transactionSize) {
        this.transactionSize = transactionSize;
    }

}