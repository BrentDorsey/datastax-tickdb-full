package com.datastax.tickdata;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.demo.utils.PropertyHelper;
import com.datastax.tickdata.engine.TickGenerator;
import com.datastax.timeseries.model.TimeSeries;

public class Main {
	private static Logger logger = LoggerFactory.getLogger(Main.class);
	private Boolean BINARY_PRODUCER_RUNNING = true;
	private Boolean CLUSTERING_PRODUCER_RUNNING = true;
	private final int BLOCKING_QUEUE_SIZE = 100;
	private final String BINARY_QUEUE_TYPE = "Binary";
	private final String CLUSTERING_QUEUE_TYPE = "Clustering";
	private final AtomicLong binaryTickerCount = new AtomicLong(0);
	private final AtomicLong binaryTimeInMillis = new AtomicLong(0);
	private final AtomicLong clustingTickerCount = new AtomicLong(0);
	private final AtomicLong clustingTimeInMillis = new AtomicLong(0);
	private final String DECIMAL_FORMAT_PATTERN = "#,###,###.###";
	private final DecimalFormat decimalFormat = new DecimalFormat(DECIMAL_FORMAT_PATTERN);

	public Main() {

		String contactPointsStr = PropertyHelper.getProperty("contactPoints", "localhost");
		// default to 4 threads: 2 Producers and 2 Consumers
		String noOfThreadsStr = PropertyHelper.getProperty("noOfThreads", "4");
		String noOfDaysStr = PropertyHelper.getProperty("noOfDays", "2");

		int noOfDays = Integer.parseInt(noOfDaysStr);
		DateTime startTime = new DateTime().minusDays(noOfDays - 1);
		logger.info("StartTime : " + startTime);

		TickDataBinaryDao binaryDao = new TickDataBinaryDao(contactPointsStr.split(","));
		TickDataDao dao = new TickDataDao(contactPointsStr.split(","));

		int noOfThreads = Integer.parseInt(noOfThreadsStr);
		// Create 2 BlockingQueues one for Binary TimeSeries data and one for Clusting TimeSeries data
		BlockingQueue<TimeSeries> binaryQueue = new ArrayBlockingQueue<TimeSeries>(BLOCKING_QUEUE_SIZE);
		BlockingQueue<TimeSeries> queue = new ArrayBlockingQueue<TimeSeries>(BLOCKING_QUEUE_SIZE);

		// Executor for Multithreading
		ExecutorService executorService = Executors.newFixedThreadPool(noOfThreads);

		// Load stock symbols from csv files
		DataLoader dataLoader = new DataLoader ();
		List<String> exchangeSymbols = dataLoader.getExchangeData();
		logger.info("No of stock symbols : " + exchangeSymbols.size());

		// Create 2 threads with Producers and start populating the BlockingQueues with TimeSeries data
		executorService.execute(new BinaryTimeSeriesProducer(binaryQueue, exchangeSymbols, startTime));
		executorService.execute(new ClusteringTimeSeriesProducer(queue, exchangeSymbols, startTime));

		// pause to ensure entries are available in the queue before starting the consumer
		sleep(5);

		// Create 2 threads with Consumers and start processing the queues and persisting data to Cassandra
		executorService.execute(new BinaryTimeSeriesConsumer(binaryDao, binaryQueue));
		executorService.execute(new ClusteringTimeSeriesConsumer(dao, queue));

		sleep(5);

		while(!queue.isEmpty() || !binaryQueue.isEmpty()){
			if(!binaryQueue.isEmpty() && BINARY_PRODUCER_RUNNING) {
				logger.info(String.format("BinaryQueue.remainingCapacity (available capacity) = %s", binaryQueue.remainingCapacity()));
			}
			if(!queue.isEmpty() && CLUSTERING_PRODUCER_RUNNING) {
				logger.info(String.format("ClusteringQueue.remainingCapacity (available capacity) = %s", queue.remainingCapacity()));
			}
			sleep(5);
		}

		if (binaryTimeInMillis.get() > 0){
			logger.info("Binary Data Loading (" + decimalFormat.format(binaryTickerCount) + " ticks) took " + binaryTimeInMillis.get()+ "ms (" + decimalFormat.format(new Double(binaryTickerCount.doubleValue()*1000)/(new Double(binaryTimeInMillis.get()).doubleValue())) + " a sec)");
		}
		if (clustingTimeInMillis.get() > 0){
			logger.info("Clustering Data Loading (" + decimalFormat.format(clustingTickerCount) + " ticks) took " + clustingTimeInMillis.get()+ "ms (" + decimalFormat.format(new Double(clustingTickerCount.doubleValue()*1000)/(new Double(clustingTimeInMillis.get()).doubleValue())) + " a sec)");
		}

		System.exit(0);
	}

	/**
	 * BinaryTimeSeriesProducer
	 *
	 * 	Populates the Binary queue with TimeSeries objects simulating multiple stocks and stock price
	 * 	values at different times during the day over a date range.
	 */
	class BinaryTimeSeriesProducer implements Runnable {

		private BlockingQueue<TimeSeries> blockingQueue;
		private List<String> exchangeSymbols;
		private DateTime startTime;

		public BinaryTimeSeriesProducer(BlockingQueue<TimeSeries> blockingQueue, List<String> exchangeSymbols, DateTime startTime) {
			this.blockingQueue = blockingQueue;
			this.exchangeSymbols = exchangeSymbols;
			this.startTime = startTime;
			logger.info("BinaryTimeSeriesProducer - Begin populating Binary BlockingQueue");
		}

		@Override
		public void run() {
			TickGenerator tickGenerator = new TickGenerator(exchangeSymbols, startTime);
			while (tickGenerator.hasNext()){
				TimeSeries next = tickGenerator.next();
				try {
					blockingQueue.put(next);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			binaryTickerCount.addAndGet(tickGenerator.getCount());
			BINARY_PRODUCER_RUNNING = false;
			logger.info(String.format("BinaryTimeSeriesProducer - TickGenerator Completed populating BlockingQueue with %s Stock Ticker values", tickGenerator.getCount()));
		}
	}

	/**
	 * BinaryTimeSeriesConsumer
	 *
	 * 	Polls the binaryQueue for TimeSeries objects simulating multiple stocks and stock prices
	 * 	recorded over a day.
	 *
	 * 	Inserts the entire TimeSeries object as a blob through the TimeSeries entries writing the individual stock ticker
	 * 	values by timestamp to:
	 * 		datastax_tickdata_binary_demo.tick_data
	 */
	class BinaryTimeSeriesConsumer implements Runnable {

		private TickDataBinaryDao binaryDao;
		private BlockingQueue<TimeSeries> binaryQueue;

		public BinaryTimeSeriesConsumer(TickDataBinaryDao binaryDao, BlockingQueue<TimeSeries> binaryQueue) {
			logger.info("Created BinaryTimeSeriesConsumer");
			this.binaryDao = binaryDao;
			this.binaryQueue = binaryQueue;
		}

		@Override
		public void run() {
			TimeSeries timeSeriesBinary;
			while(true){
				timeSeriesBinary = binaryQueue.poll();
				if (timeSeriesBinary!=null){
					try {
						Instant start = Instant.now();
						this.binaryDao.insertTimeSeries(timeSeriesBinary);
						binaryTimeInMillis.addAndGet(Duration.between(start, Instant.now()).toMillis());
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	/**
	 * ClusteringTimeSeriesProducer
	 *
	 * 	Populates the Clustering queue with TimeSeries objects simulating multiple stocks and stock price
	 * 	values at different times during the day over a date range.
	 */
	class ClusteringTimeSeriesProducer implements Runnable {

		private BlockingQueue<TimeSeries> blockingQueue;
		private List<String> exchangeSymbols;
		private DateTime startTime;

		public ClusteringTimeSeriesProducer(BlockingQueue<TimeSeries> blockingQueue, List<String> exchangeSymbols, DateTime startTime) {
			this.blockingQueue = blockingQueue;
			this.exchangeSymbols = exchangeSymbols;
			this.startTime = startTime;
			logger.info("ClusteringTimeSeriesProducer - Begin populating Clustering BlockingQueue");
		}

		@Override
		public void run() {
			TickGenerator tickGenerator = new TickGenerator(exchangeSymbols, startTime);
			while (tickGenerator.hasNext()){
				TimeSeries next = tickGenerator.next();
				try {
					blockingQueue.put(next);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			clustingTickerCount.addAndGet(tickGenerator.getCount());
			CLUSTERING_PRODUCER_RUNNING = false;
			logger.info(String.format("ClusteringTimeSeriesProducer - TickGenerator Completed populating BlockingQueue with %s Stock Ticker values", tickGenerator.getCount()));
		}
	}

	/**
	 * ClusteringTimeSeriesConsumer
	 *
	 * 	Polls the queue for TimeSeries objects simulating multiple stocks and stock prices
	 * 	recorded over a day.
	 *
	 * 	Loops through the TimeSeries entries writing the individual stock ticker
	 * 	values by timestamp to:
	 * 		datastax_tickdata_demo.tick_data
	 */
	class ClusteringTimeSeriesConsumer implements Runnable {

		private TickDataDao dao;
		private BlockingQueue<TimeSeries> queue;

		public ClusteringTimeSeriesConsumer(TickDataDao dao, BlockingQueue<TimeSeries> queue) {
			logger.info("Created ClusteringTimeSeriesConsumer");
			this.dao = dao;
			this.queue = queue;
		}

		@Override
		public void run() {
			TimeSeries timeSeries;
			while(true){
				timeSeries = queue.poll();
				if (timeSeries!=null){
					try {
						Instant start = Instant.now();
						this.dao.insertTimeSeries(timeSeries);
						clustingTimeInMillis.addAndGet(Duration.between(start, Instant.now()).toMillis());

					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
	}


	private void sleep(int seconds) {
		try {
			Thread.sleep(seconds * 1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		new Main();
	}
}
