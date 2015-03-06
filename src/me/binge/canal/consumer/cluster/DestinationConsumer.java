package me.binge.canal.consumer.cluster;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.protocol.CanalEntry.TransactionBegin;
import com.alibaba.otter.canal.protocol.CanalEntry.TransactionEnd;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.InvalidProtocolBufferException;

public class DestinationConsumer {

	private static final Logger logger = LoggerFactory.getLogger(DestinationConsumer.class);

	protected static String                   context_format     = null;
    protected static String                   row_format         = null;
    protected static String                   transaction_format = null;
    protected static final String             SEP                = SystemUtils.LINE_SEPARATOR;

	static {
        context_format = SEP + "****************************************************" + SEP;
        context_format += "* Batch Id: [{}] ,count : [{}] , memsize : [{}] , Time : {}" + SEP;
        context_format += "* Start : [{}] " + SEP;
        context_format += "* End : [{}] " + SEP;
        context_format += "****************************************************" + SEP;

        row_format = SEP
                     + "----------------> binlog[{}:{}] , name[{},{}] , eventType : {} , executeTime : {} , delay : {}ms"
                     + SEP;

        transaction_format = SEP + "================> binlog[{}:{}] , executeTime : {} , delay : {}ms" + SEP;

    }


	private int batchSize;
	private String zkHosts;
	private String destination;
	private Callable<Void> succCallback;
	private Callable<Void> errCallback;

	private Thread thread;
	private boolean running = false;

	public DestinationConsumer() {
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public String getZkHosts() {
		return zkHosts;
	}

	public void setZkHosts(String zkHosts) {
		this.zkHosts = zkHosts;
	}

	public String getDestination() {
		return destination;
	}

	public void setDestination(String destination) {
		this.destination = destination;
	}

	public Callable<Void> getSuccCallback() {
		return succCallback;
	}

	public void setSuccCallback(Callable<Void> succCallback) {
		this.succCallback = succCallback;
	}

	public Callable<Void> getErrCallback() {
		return errCallback;
	}

	public void setErrCallback(Callable<Void> errCallback) {
		this.errCallback = errCallback;
	}

	public void start() {

		thread = new Thread(new Runnable() {

			public void run() {
				process(zkHosts, destination);
			}
		});

		// thread.setUncaughtExceptionHandler(handler);
		thread.start();
		running = true;
		try {
			succCallback.call();
		} catch (Exception e) {
		}
	}

	protected void stop() {
		if (!running) {
			return;
		}
		running = false;
		if (thread != null) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				// ignore
			}
		}
		try {
			errCallback.call();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	protected void process(String zkHosts, String destination) {
		CanalConnector connector = CanalConnectors.newClusterConnector(zkHosts,
				destination, "", "");
		while (running) {
			try {
				connector.connect();
				connector.subscribe();
				while (running) {
					Message message = connector.getWithoutAck(batchSize); // 获取指定数量的数据
					long batchId = message.getId();
					int size = message.getEntries().size();
					if (batchId == -1 || size == 0) {
						try {
							Thread.sleep(3000);
						} catch (InterruptedException e) {
						}
					} else {
						try {
							printSummary(message, batchId, size);
							printEntry(message.getEntries());
						} catch (Exception e) {
							connector.rollback(batchId); // 处理失败, 回滚数据
							continue;
						}

					}

					connector.ack(batchId); // 提交确认
				}
			} catch (Exception e) {
				//throw new CanalException(e); // 向上抛出, 由上层感知并释放资源.
				e.printStackTrace();
			} finally {
				connector.disconnect();
			}
		}
	}

	private void printSummary(Message message, long batchId, int size) {
		long memsize = 0;
		for (Entry entry : message.getEntries()) {
			memsize += entry.getHeader().getEventLength();
		}

		String startPosition = null;
		String endPosition = null;
		if (!CollectionUtils.isEmpty(message.getEntries())) {
			startPosition = buildPositionForDump(message.getEntries().get(0));
			endPosition = buildPositionForDump(message.getEntries().get(
					message.getEntries().size() - 1));
		}

		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		logger.info(context_format, new Object[] { batchId, size, memsize,
				format.format(new Date()), startPosition, endPosition });
	}

	protected String buildPositionForDump(Entry entry) {
		long time = entry.getHeader().getExecuteTime();
		Date date = new Date(time);
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		return entry.getHeader().getLogfileName() + ":"
				+ entry.getHeader().getLogfileOffset() + ":"
				+ entry.getHeader().getExecuteTime() + "("
				+ format.format(date) + ")";
	}

	protected void printEntry(List<Entry> entrys) {
		for (Entry entry : entrys) {
			long executeTime = entry.getHeader().getExecuteTime();
			long delayTime = new Date().getTime() - executeTime;

			if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN
					|| entry.getEntryType() == EntryType.TRANSACTIONEND) {
				if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN) {
					TransactionBegin begin = null;
					try {
						begin = TransactionBegin.parseFrom(entry
								.getStoreValue());
					} catch (InvalidProtocolBufferException e) {
						throw new RuntimeException(
								"parse event has an error , data:"
										+ entry.toString(), e);
					}
					// 打印事务头信息，执行的线程id，事务耗时
					logger.info(
							transaction_format,
							new Object[] {
									entry.getHeader().getLogfileName(),
									String.valueOf(entry.getHeader()
											.getLogfileOffset()),
									String.valueOf(entry.getHeader()
											.getExecuteTime()),
									String.valueOf(delayTime) });
					logger.info(" BEGIN ----> Thread id: {}",
							begin.getThreadId());
				} else if (entry.getEntryType() == EntryType.TRANSACTIONEND) {
					TransactionEnd end = null;
					try {
						end = TransactionEnd.parseFrom(entry.getStoreValue());
					} catch (InvalidProtocolBufferException e) {
						throw new RuntimeException(
								"parse event has an error , data:"
										+ entry.toString(), e);
					}
					// 打印事务提交信息，事务id
					logger.info("----------------\n");
					logger.info(" END ----> transaction id: {}",
							end.getTransactionId());
					logger.info(
							transaction_format,
							new Object[] {
									entry.getHeader().getLogfileName(),
									String.valueOf(entry.getHeader()
											.getLogfileOffset()),
									String.valueOf(entry.getHeader()
											.getExecuteTime()),
									String.valueOf(delayTime) });
				}

				continue;
			}

			if (entry.getEntryType() == EntryType.ROWDATA) {
				RowChange rowChage = null;
				try {
					rowChage = RowChange.parseFrom(entry.getStoreValue());
				} catch (Exception e) {
					throw new RuntimeException(
							"parse event has an error , data:"
									+ entry.toString(), e);
				}

				EventType eventType = rowChage.getEventType();

				logger.info(
						row_format,
						new Object[] {
								entry.getHeader().getLogfileName(),
								String.valueOf(entry.getHeader()
										.getLogfileOffset()),
								entry.getHeader().getSchemaName(),
								entry.getHeader().getTableName(),
								eventType,
								String.valueOf(entry.getHeader()
										.getExecuteTime()),
								String.valueOf(delayTime) });

				if (eventType == EventType.QUERY || rowChage.getIsDdl()) {
					logger.info(" sql ----> " + rowChage.getSql() + SEP);
					continue;
				}

				for (RowData rowData : rowChage.getRowDatasList()) {
					if (eventType == EventType.DELETE) {
						printColumn(rowData.getBeforeColumnsList());
					} else if (eventType == EventType.INSERT) {
						printColumn(rowData.getAfterColumnsList());
					} else {
						printColumn(rowData.getAfterColumnsList());
					}
				}
			}
		}
	}

	protected void printColumn(List<Column> columns) {
		for (Column column : columns) {
			StringBuilder builder = new StringBuilder();
			builder.append(column.getName() + " : " + column.getValue());
			builder.append("    type=" + column.getMysqlType());
			if (column.getUpdated()) {
				builder.append("    update=" + column.getUpdated());
			}
			builder.append(SEP);
			logger.info(builder.toString());
		}
	}
}
