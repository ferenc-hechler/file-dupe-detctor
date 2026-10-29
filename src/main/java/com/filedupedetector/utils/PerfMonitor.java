package com.filedupedetector.utils;

public class PerfMonitor {

	long startTime;
	
	public PerfMonitor() {
		startTime = System.nanoTime();
	}
	
	public void reset() {
		startTime = System.nanoTime();
	}
	
	public double getElapsedSeconds() {
		long stopTime = System.nanoTime();
		double seconds = (stopTime - startTime) / 1_000_000_000.0;
		return seconds;
	}
	
	public void warnIfElapsedSecondsExceeds(long thresholdMillis, String message) {
		long stopTime = System.nanoTime();
		long elapsedMillis = (stopTime - startTime) / 1_000_000;
		if (elapsedMillis > thresholdMillis) {
			System.out.printf("WARNING: %s (elapsed time: %.3f sec)%n", message, elapsedMillis / 1000.0);
		}
	}
	
	
	
}
