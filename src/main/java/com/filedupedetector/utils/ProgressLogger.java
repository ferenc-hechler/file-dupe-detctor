package com.filedupedetector.utils;

import java.util.Locale;

public class ProgressLogger<T> {

	static interface LogCallback {
		String progressMessage();
	}
	
	static interface ParamLogCallback<T> {
		String progressMessage(T param);
	}
	
	private int logIntervalSeconds;
	private long estimatedTotalCount;

	private long nextLogTime;
	private long count;

	
	public ProgressLogger() {
		this(15, -1);
	}
	
	public ProgressLogger(int logIntervalSeconds) {
		this(logIntervalSeconds, -1);
	}
	
	public ProgressLogger(int logIntervalSeconds, long estimatedTotalCount) {
		this.logIntervalSeconds = logIntervalSeconds;
		this.estimatedTotalCount = estimatedTotalCount;
		this.nextLogTime = System.nanoTime();
		this.count = 0;
	}
	
	public void progress(LogCallback infoCallback) {
		count++;
		if (System.nanoTime() >= nextLogTime) {
			String progressMessage = infoCallback.progressMessage();
			if (estimatedTotalCount > 0) {
				System.out.printf(Locale.ROOT, "%d/%d (%.2f%%) - %s%n", count, estimatedTotalCount, 100.0 * count / estimatedTotalCount, progressMessage);
			} else {
				System.out.printf(Locale.ROOT, "%d - %s%n", count, progressMessage);
			}
			nextLogTime = System.nanoTime() + logIntervalSeconds * 1_000_000_000L;
		};
	}


	public void progress(T param, ParamLogCallback infoCallback) {
		count++;
		if (System.nanoTime() >= nextLogTime) {
			String progressMessage = infoCallback.progressMessage(param);
			if (estimatedTotalCount > 0) {
				System.out.printf(Locale.ROOT, "%d/%d (%.2f%%) - %s%n", count, estimatedTotalCount, 100.0 * count / estimatedTotalCount, progressMessage);
			} else {
				System.out.printf(Locale.ROOT, "%d - %s%n", count, progressMessage);
			}
			nextLogTime = System.nanoTime() + logIntervalSeconds * 1_000_000_000L;
		};
	}

	public void finished(LogCallback infoCallback) {
		String progressMessage = infoCallback.progressMessage();
		System.out.printf(Locale.ROOT, "%d/%d (%.2f%%) - %s%n", count, count, 100.0, progressMessage);
	}

	public void finished(T param, ParamLogCallback infoCallback) {
		String progressMessage = infoCallback.progressMessage(param);
		System.out.printf(Locale.ROOT, "%d/%d (%.2f%%) - %s%n", count, count, 100.0, progressMessage);
	}

	public static void main(String[] args) {
		int MAX = 25;
		ProgressLogger<Integer> logger;
		logger = new ProgressLogger<>(5, MAX);
		for (int i = 0; i < MAX; i++) {
			int j = i;
			logger.progress(()->"at Step "+j);
			try {
				Thread.sleep(500); // Simulate work
			} catch (InterruptedException e) {}
		}
		logger.finished(()->"at Step "+-1);

		logger = new ProgressLogger<>(5);
		for (int i = 0; i < MAX; i++) {
			int j = i;
			logger.progress(()->"at Step "+j);
			try {
				Thread.sleep(500); // Simulate work
			} catch (InterruptedException e) {}
		}
		logger.finished(()->"at Step "+-1);
		logger = new ProgressLogger<>(5, MAX);
		for (int i = 0; i < MAX; i++) {
			logger.progress(i, (n)->"at Step "+n);
			try {
				Thread.sleep(500); // Simulate work
			} catch (InterruptedException e) {}
		}
		logger.finished(-1, (n)->"at Step "+n);

		logger = new ProgressLogger<>(5);
		for (int i = 0; i < MAX; i++) {
			logger.progress(i, (n)->"at Step "+n);
			try {
				Thread.sleep(500); // Simulate work
			} catch (InterruptedException e) {}
		}
		logger.finished(-1, (n)->"at Step "+n);
	}
	
}
