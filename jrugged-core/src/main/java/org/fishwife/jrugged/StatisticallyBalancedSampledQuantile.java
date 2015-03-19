/* StatisticallyBalancedPerformanceMonitor.java
 * 
 * Copyright 2009-2012 Comcast Interactive Media, LLC.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fishwife.jrugged;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link StatisticallyBalancedSampledQuantile} provides a way to compute
 * approximate quantile measurements across a set of samples
 * reported to an instance. By default, these samples are
 * taken across the instance's lifetime, but a window can be
 * configured to keep samples across just across that trailing
 * time span (for example, getting a quantile across the last
 * minute). We use an algorithm that keeps a fixed maximum number
 * of samples that selects uniformly from all reported samples so
 * far (thus representing a statistically appropriate sampling
 * of the entire population). For the windowed version, we keep
 * track of how many samples had been seen over twentieths of the
 * window on a rolling basis to ensure that samples are chosen
 * appropriately.
 */
public class StatisticallyBalancedSampledQuantile {

    private boolean StatisticalBalance = false;

	private static final int NUM_WINDOW_SEGMENTS = 20;
    private static final int DEFAULT_MEM_SIZE = 2;

	private List<Sample> samples = new ArrayList<Sample>();

	private AtomicLong samplesSeen = new AtomicLong(0L);
	private int maxSamples = DEFAULT_MEM_SIZE*125;
    private int maxMem = DEFAULT_MEM_SIZE;
	private Long windowMillis;

	private LinkedList<Sample> windowSegments;
    Random rand = new Random();

	/**
     * Creates a <code>SampleQuantile</code> that keeps a
	 * default number of samples across its lifetime.
	 */
	public StatisticallyBalancedSampledQuantile() {
		this(DEFAULT_MEM_SIZE, true);
	}

	/**
     * Creates a <code>SampleQuantile</code> that keeps a
	 * given maximum number of samples across its lifetime.
     *
     * @param max_mem the maximum number of memory in kilobytes to allocate to samples
	 */
	public StatisticallyBalancedSampledQuantile(int max_mem, boolean balance) {
        StatisticalBalance = balance;
        if (StatisticalBalance) {
            maxMem = max_mem;
            this.maxSamples = max_mem * 125;
        }
        else{
            //how to properly use with feature flag?
            maxSamples = 200;
        }

	}

	/**
     * Creates a <code>SampleQuantile</code> that keeps a
	 * default number of samples across the specified time
	 * window.
     *
     * @param windowLength size of time window to hold onto samples
     * @param units indication of what time units windowLength is specified in
	 */
	public StatisticallyBalancedSampledQuantile(long windowLength, TimeUnit units, boolean balance) {
        this(DEFAULT_MEM_SIZE, windowLength, units, balance);
	}

	/**
     * Creates a <code>SampleQuantile</code> that keeps a
	 * given maximum number of samples across the specified time
	 * window.
     *
     * @param max_mem the maximum number of samples to keep inside of windowLength
     * @param windowLength size of time window to hold onto samples
     * @param units indication of what time units windowLength is specified in
	 */
	public StatisticallyBalancedSampledQuantile(int max_mem, long windowLength, TimeUnit units, boolean balance) {
            this(max_mem, windowLength, units, System.currentTimeMillis(), balance);

	}


    StatisticallyBalancedSampledQuantile(long windowLength, TimeUnit units, long now) {
        this.maxSamples = maxSamples;
        setWindowMillis(windowLength, units);
        windowSegments = new LinkedList<Sample>();
        windowSegments.offer(new Sample(samplesSeen.get(), now));
    }

	StatisticallyBalancedSampledQuantile(int max_mem, long windowLength, TimeUnit units, long now, boolean balance) {
        StatisticalBalance = balance;
		this.maxSamples = max_mem * 125;
		setWindowMillis(windowLength, units);
		windowSegments = new LinkedList<Sample>();
		windowSegments.offer(new Sample(samplesSeen.get(), now));
	}

	private void setWindowMillis(long windowLength, TimeUnit units) {
		switch(units) {
		    case NANOSECONDS: windowMillis = windowLength / 1000000; break;
		    case MICROSECONDS: windowMillis = windowLength / 1000; break;
		    case MILLISECONDS: windowMillis = windowLength; break;
		    case SECONDS: windowMillis = windowLength * 1000; break;
            default: throw new IllegalArgumentException("Unknown TimeUnit specified");
		}
	}

	/**
     * Returns the median of the samples seen thus far.
     *
     * @return long The median measurement
     */
	public long getMedian() {
		return getPercentile(50);
	}

	/**
     * Returns the <code>i</code>th percentile of the samples seen
	 * thus far. This is equivalent to <code>getQuantile(i,100)</code>.
     *
	 * @param i must be 0 < i < 100
	 * @return i-th percentile, or 0 if there is no data
	 * @throws StatisticallyBalancedSampledQuantile.QuantileOutOfBoundsException if i <= 0 or i >= 100
	 */
	public long getPercentile(int i) {
		return getPercentile(i, System.currentTimeMillis());
	}

	long getPercentile(int i, long now) {
		return getQuantile(i, 100, now);
	}

	/**
     * Returns the <code>k</code>th <code>q</code>-quantile of the samples
	 * seen thus far.
     *
	 * @param q must be >= 2
	 * @param k must be 0 < k < q
	 * @return k-th q-quantile, or 0 if there is no data
	 * @throws StatisticallyBalancedSampledQuantile.QuantileOutOfBoundsException if k <= 0 or k >= q
	 */
	public long getQuantile(int k, int q) {
		return getQuantile(k, q, System.currentTimeMillis());
	}
	
	long getQuantile(int k, int q, long now) {
		if (k <= 0 || k >= q) throw new QuantileOutOfBoundsException();
		List<Sample> validSamples = getValidSamples(now);
		if (validSamples.size() == 0) return 0;
		Collections.sort(validSamples);
		double targetIndex = (validSamples.size() * k) / (q * 1.0);
		if (validSamples.size() % 2 == 1) {
			return validSamples.get((int)Math.ceil(targetIndex) - 1).data;
		}
		int i0 = (int)Math.floor(targetIndex) - 1;
		return (validSamples.get(i0).data + validSamples.get(i0+1).data) / 2;
	}

	private List<Sample> getValidSamples(long now) {
		if (windowMillis == null) return samples;
		long deadline = now - windowMillis;
		List<Sample> validSamples = new ArrayList<Sample>();
		for(Sample sample : samples) {
			if (sample.timestamp >= deadline) {
				validSamples.add(sample);
			}
		}
		return validSamples;
	}

	/**
     * Reports the number of samples currently held by this
	 * <code>SampleQuantile</code>.
     *
	 * @return int
	 */
	public int getNumSamples() {
		return samples.size();
	}
	
	/**
     * Reports a sample measurement to be included in the quantile
	 * calculations.
     *
	 * @param l specific measurement
	 */
	public void addSample(long l) {
		addSample(l, System.currentTimeMillis());
	}

	private synchronized void updateWindowSegments(long now) {
		if (windowMillis == null) return;
		long deadline = now - windowMillis;
		long segmentSize = windowMillis / NUM_WINDOW_SEGMENTS;
		while(windowSegments.size() > 0 && windowSegments.peek().timestamp < deadline) {
			windowSegments.remove();
		}
		long mostRecentSegmentTimestamp = (windowSegments.size() > 0) ? 
		    windowSegments.getLast().timestamp : 0L;
		if (windowSegments.size() == 0 
			|| now - mostRecentSegmentTimestamp > segmentSize) {
			windowSegments.offer(new Sample(samplesSeen.get(), now));
		}
	}
	
	private long getEffectiveSamplesSeen() {
		if (windowMillis == null) return samplesSeen.get();
		return (samplesSeen.get() - windowSegments.getFirst().data);
	}
	
	void addSample(long l, long now) {
        double addratio = maxSamples * 1.0 / getEffectiveSamplesSeen();

        if (StatisticalBalance){
            addratio = .5;
        }
		samplesSeen.getAndIncrement();
		updateWindowSegments(now);
		if (samples.size() < maxSamples) {
			samples.add(new Sample(l, now));
			return;
		}
		if (rand.nextDouble() < addratio) {
			int idx = rand.nextInt(maxSamples);
			samples.set(idx, new Sample(l, now));
		}
	}
	
	private static class Sample implements Comparable<Sample> {
		public long data;
		public long timestamp;
		
		public Sample(long data, long timestamp) {
			this.data = data;
			this.timestamp = timestamp;
		}
		
		public int compareTo(Sample other) {
			if (other.data > data) return -1;
			if (other.data < data) return 1;
			if (other.timestamp > timestamp) return -1;
			if (other.timestamp < timestamp) return 1;
			return 0;
		}
	}
	
	public static class QuantileOutOfBoundsException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
