/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 *
 * @authors Andrew Dinn
 */

package org.jboss.churn;

import java.io.PrintStream;

/**
 * A LogHistogram records a large series of timings as a logarithmically
 * scaled histogram. It is used to identify wide variations in measurements
 * of elapsed time for execution of a task. The assumption is that the task
 * is executing an algorithm with a relatively constant algorithmic complexity,
 * hence that variations are introduced by the task's runtime rather than by
 * the algorithm itself. It is also assumed that these variations occur with
 * a relatively low frequency but may increase the elapsed time by up to 5
 * orders of magnitude e,g, from 1 msec to 100 secs).
 *
 * Before a LogHistogram can be used it needs to be calibrated by supplying an
 * initial set of timings. A 'normal' expected value is computed by sorting
 * this initial sequence and then taking the mean of the smallest half of the
 * resulting ordered sequence, thus avoiding.
 *
 * Subsequent readings are normalised by dividing by the expected value.
 * This normalised value is then counted in one of the buckets in the
 * histogram's range. The initial bucket counts values in the range [0, 2),
 * although readings ought rarely to be much less than 1.
 * Suibsequent buckets count values in the range [2, 4), [4, 8) etc. The
 * total number of buckets is configurable but it defaults to 16, resulting
 * in a maximum bucket counting values in the range [32,768, 65535). Values
 * greater than the last bucket maximum are counted separately.
 *
 * It is possible to configure the histogram so that it counts values within
 * buckets using a linear scale. So, the first bucket can be split to count
 * values in the ranges [0, 1.1), [1.1, 1,2) . . . [1.9, 2.0), the second
 * bucket counts values in the ranges [2.0, 2.2), [2.2, 2.4) . . . [3.8, 4.4)
 * and so on.
 *
 * Once recording is complete the histogram can be queried to report total
 * counts in each of the logarithmic buckets and, if so configured, linear
 * counts within each bucket.
 */
public class LogHistogram
{
    // public api

    /**
     * the default number of buckets in the histogram. this means that the value ranges
     * extend from [0, 2) to [32,768, 65535)
     */
    public final static int BUCKET_COUNT_DEFAULT = 16;

    /**
     * the default number of linear subdivions of each bucket range.
     */
    public final static int LINEAR_SCALE_DEFAULT = 10;

    /**
     * create an undivided log histogram with the default number of buckets.
     *
     * redirects to {@link #LogHistogram}(BUCKET_COUNT_DEFAULT, false, LINEAR_SCALE_DEFAULT)
     */
    public LogHistogram()
    {
        this(BUCKET_COUNT_DEFAULT, false, LINEAR_SCALE_DEFAULT);
    }

    /**
     * create an undivided log histogram with the bucketTotal buckets.
     *
     * redirects to {@link #LogHistogram}(bucketTotal, false, LINEAR_SCALE_DEFAULT)
     *
     * @param bucketTotal the number of buckets to employ
     */
    public LogHistogram(int bucketTotal)
    {
        this(bucketTotal, false, LINEAR_SCALE_DEFAULT);
    }

    /**
     * create a log histogram with the bucketTotal buckets possibly divided into
     * the default number of intervals.
     *
     * redirects to {@link #LogHistogram}(bucketTotal, false, LINEAR_SCALE_DEFAULT)
     *
     * @param bucketTotal the number of buckets to employ
     * @param subDivide true iff buckets should be divided into intervals
     */
    public LogHistogram(int bucketTotal, boolean subDivide)
    {
        this(bucketTotal, false, LINEAR_SCALE_DEFAULT);
    }

    /**
     * create a log histogram possibly divided into intervals with the default number of
     * buckets and intervals.
     *
     * redirects to {@link #LogHistogram}(BUCKET_COUNT_DEFAULT, subDivide, LINEAR_SCALE_DEFAULT)
     *
     * @param subDivide true iff buckets should be divided into intervals
     */
    public LogHistogram(boolean subDivide)
    {
        this(BUCKET_COUNT_DEFAULT, subDivide, LINEAR_SCALE_DEFAULT);
    }

    /**
     * create a log histogram possibly divided into intervals with the default number of
     * buckets and a given number of intervals.
     *
     * redirects to {@link #LogHistogram}(BUCKET_COUNT_DEFAULT, subDivide, intervalTotal)
     *
     * @param subDivide true iff buckets should be divided into intervals
     * @param intervalTotal the number of intervals to employ
     */
    public LogHistogram(boolean subDivide, int intervalTotal)
    {
        this(BUCKET_COUNT_DEFAULT, subDivide, intervalTotal);
    }

    /**
     * create a log histogram possibly divided into intervals with the given number of
     * buckets and intervals.
     *
     * @param bucketTotal the number of buckets to employ in range [1, 63] or 0 for default
     * @param subDivide true iff buckets should be divided into intervals
     * @param intervalTotal the number of intervals to employ in range [2, ...) or 0 for default
     */
    public LogHistogram(int bucketTotal, boolean subDivide, int intervalTotal) throws IllegalArgumentException
    {
        if (bucketTotal < 0) {
            throw new IllegalArgumentException("bucket count must be positive (or 0 for default)");
        } else if (bucketTotal == 0) {
            bucketTotal = 16;
        } else if (bucketTotal > 63) {
            throw new IllegalArgumentException("bucket count must not be greater than 63");
        }
        this.bucketTotal = bucketTotal;

        if (subDivide) {
            if (intervalTotal == 0) {
                intervalTotal = LINEAR_SCALE_DEFAULT;
            } else if (intervalTotal <= 1) {
                throw new IllegalArgumentException("linear subdivision scale must be greater than 1 (or 0 for default)");
            }
            this.intervalTotal = intervalTotal;
        } else {
            //  an intervaklcount of 1 means we just use simplebuckets
            this.intervalTotal = 1;
        }

        sampleCount = 0;

        createBuckets();
    }

    /**
     * count this value in the appropriate bucket
     *
     * @param value the value to be counted
     */
    public void count(long value)
    {
        int bucket = computeBucket(value);
        buckets[bucket].count(value);
        sampleCount++;
    }

    /**
     * return the lowest possible value in a given bucket
     *
     * @param bucket
     * @return
     */
    public long getLow(int bucket)
    {
        if (bucket < 0 || bucket >= bucketTotal) {
            throw new IllegalArgumentException("invalid bucket count " + bucket);
        }
        // special case
        if (bucket == 0) {
            return 0;
        }
        return (1L << bucket);
    }

    /**
     * return the highest possible value in a given bucket
     *
     * @param bucket
     * @return
     */
    public long getHigh(int bucket)
    {
        if (bucket < 0 || bucket >= bucketTotal) {
            throw new IllegalArgumentException("invalid bucket count " + bucket);
        }
        // special case
        if (bucket == bucketTotal) {
            return Long.MAX_VALUE;
        }

        long low = (1L << bucket);
        return low + (low - 1);
    }

    /**
     * return the lowest possible value in a given bucket interval
     *
     * @param bucket
     * @return
     */
    public long getLow(int bucket, int interval)
    {
        if (bucket < 0 || bucket >= bucketTotal) {
            throw new IllegalArgumentException("invalid bucket count " + bucket);
        }
        if (intervalTotal == 1) {
            throw new IllegalArgumentException("cannot retrieve interval low value from undivided histogram");
        }
        // special case
        if (bucket == 0 && interval == 0) {
            return 0;
        }
        long low = (1L << bucket);
        double dLow = (double)low;
        double dStart = dLow + ((dLow * interval) / intervalTotal);
        long lStart = (long)dStart;
        double ddStart = (double)lStart;
        if (ddStart < ddStart) {
            lStart++;
        }
        return lStart;
    }

    /**
     * return the highest possible value in a given bucket interval
     *
     * @param bucket
     * @return
     */
    public long getHigh(int bucket, int interval)
    {
        if (bucket < 0 || bucket >= bucketTotal) {
            throw new IllegalArgumentException("invalid bucket count " + bucket);
        }
        if (intervalTotal == 1) {
            throw new IllegalArgumentException("cannot retrieve interval low value from undivided histogram");
        }
        // special case
        if (bucket == bucketTotal && interval == (intervalTotal - 1)) {
            return Long.MAX_VALUE;
        }

        long low = (1L << bucket);
        double dLow = (double)low;
        double dEnd = dLow + ((dLow * (interval + 1)) / intervalTotal);
        long lEnd = (long)dEnd;
        double ddEnd = (double)dEnd;
        if (dEnd < ddEnd) {
            lEnd++;
        }
        return lEnd;
    }

    /**
     * get the count for a given bucket
     *
     * @param bucket
     * @return
     */
    public long getCount(int bucket)
    {
        if (bucket < 0 || bucket >= bucketTotal) {
            throw new IllegalArgumentException("invalid bucket count " + bucket);
        }
        return buckets[bucket].getCount();
    }

    /**
     * get the count for an interval in a given bucket
     * @param bucket the bucket
     * @param interval the interval
     * @return
     */
    public long getCount(int bucket, int interval)
    {
        if (intervalTotal <= 1) {
            throw new IllegalArgumentException("cannot retrieve interval count from undivided histogram");
        }
        if (bucket < 0 || bucket >= bucketTotal) {
            throw new IllegalArgumentException("invalid bucket count " + bucket);
        }
        if (interval < 0 || interval >= intervalTotal) {
            throw new IllegalArgumentException("invalid interval count " + interval);
        }
        return buckets[bucket].getIntervalCount(interval);
    }

    /**
     * get the number ofsamples included in the histogram
     *
     * @return
     */
    public long getSampleCount()
    {
        return sampleCount;
    }

    /**
     * include values from some other histograminto this histogram.
     * @param other the histogram whose value should be accumulated in this histogram.
     * it must have the same bucket count and interval count as this histogram.
     */
    public void accumulate(LogHistogram other)
    {
        if (other.bucketTotal != this.bucketTotal ||
                other.intervalTotal != this.intervalTotal) {
            throw new IllegalArgumentException("incompatible histograms");
        }

        for (int bucket = 0; bucket < bucketTotal; bucket++) {
            buckets[bucket].accumulate(other.buckets[bucket]);
        }
        sampleCount += other.sampleCount;
    }

    // private implementation

    private int bucketTotal;

    private int intervalTotal;

    private long sampleCount;

    Bucket[] buckets;

    private void createBuckets()
    {
        this.buckets = new Bucket[bucketTotal];
        if (intervalTotal == 1) {
            for (int i = 0; i < bucketTotal; i++) {
                buckets[i]= new SimpleBucket();
            }
        } else {
            for (int i = 0; i < bucketTotal; i++) {
                buckets[i]= new IntervalBucket((1L << i), intervalTotal);
            }
        }
    }

    private int computeBucket(long value)
    {
        int bucket = 0;
        int high = 1 << 1;
        while (bucket < bucketTotal - 1 && value >= high) {
            bucket++;
            high <<= 1;
        }
        return bucket;
    }

    public void printTo(PrintStream str)
    {
        StringBuilder builder = new StringBuilder();
        printTo(builder);
        str.print(builder.toString());
    }

    public void printTo(StringBuilder builder)
    {
        builder.append("samples : " + sampleCount);
        builder.append('\n');
        for (int bucketIdx = 0; bucketIdx < bucketTotal; bucketIdx++) {
            Bucket bucket = buckets[bucketIdx];
            long low = 1L << bucketIdx;
            long high = low + (low - 1);
            long count = bucket.getCount();
            if (count > 0) {
                builder.append("[");
                if (low == 1) {
                    builder.append(0);
                } else {
                    builder.append(low);
                }
                builder.append(",");
                if (high == Long.MAX_VALUE) {
                    builder.append(high);
                    builder.append("]");
                } else {
                    builder.append(high + 1);
                    builder.append(")");
                }
                builder.append(" ==> ");
                builder.append(count);
                builder.append('\n');
                if (intervalTotal > 1) {
                    double dLow = low; // start is also the interval width
                    builder.append("--------\n");
                    for (int interval = 0; interval < intervalTotal; interval++) {
                        long intervalCount = bucket.getIntervalCount(interval);
                        if (intervalCount > 0) {
                            double dStart = dLow + ((dLow * interval) / intervalTotal);
                            double dEnd = dLow + ((dLow * (interval + 1)) / intervalTotal);
                            // we need to print the integer interval containing these two double values
                            // e.g. if we have [4.0, 4.4) we need to print it as [4,5)
                            // while if we have [4.8,5.2) we need to print it as [5,6)
                            // we should never see something like [5.6,6.0)
                            // so convert to long
                            long lStart = (long)dStart;
                            long lEnd = (long)dEnd;
                            // now back to double so we can see if the floor value equals the original
                            double ddStart = (double)lStart;
                            double ddEnd = (double)lEnd;
                            if (ddStart < dStart) {
                                lStart++;
                            }
                            if (ddEnd < dEnd) {
                                lEnd++;
                            }
                            builder.append("  [");
                            if (lStart == 1) {
                                builder.append(0);
                            } else {
                                builder.append(lStart);
                            }
                            builder.append(",");
                            builder.append(lEnd);
                            builder.append(") ==> ");
                            builder.append(intervalCount);
                            builder.append('\n');
                        }
                    }
                    builder.append("--------\n");
                }
            }
        }
    }

    private static interface Bucket
    {
        public void count(long value);
        public long getCount();
        public long getIntervalCount(int interval);
        public void accumulate(Bucket other);
    }

    private static class SimpleBucket implements Bucket
    {
        private long count;

        public SimpleBucket()
        {
            count =  0;
        }

        public void count(long value)
        {
            count++;
        }
        public long getCount()
        {
            return count;
        }
        public long getIntervalCount(int interval)
        {
            throw new Error("Invalid call to SimpleBucket.getIntervalCount");
        }

        public void accumulate(Bucket other) {
            count += other.getCount();
        }
    }

    private static class IntervalBucket implements Bucket
    {
        private long count;
        private long[] intervalCounts;
        private int intervalTotal;
        private long low;
        private long high;

        public IntervalBucket(long low, int intervalTotal)
        {
            intervalCounts = new long[intervalTotal];
            this.intervalTotal = intervalTotal;
            this.low = low;
            this.high =low + (low - 1);
            this.count = 0;
        }

        public void count(long value)
        {
            count++;
            if (value > high || value < 0) {
                intervalCounts[intervalTotal - 1]++;
            } else if (value <= low) {
                intervalCounts[0]++;
            } else {
                // we use doubles here because using longs will accumulate results
                // in only certain intervals when intervalDiff is at the low end
                // and risks long overflow when it is at the high end
                double diff = (value - low);
                double intervalWidth = low;
                // convert back to int to derive the interval index
                int interval = (int) ((intervalTotal * diff) / intervalWidth);
                intervalCounts[interval]++;
            }
        }
        public long getCount()
        {
            return count;
        }

        public long getIntervalCount(int interval)
        {
            return intervalCounts[interval];
        }

        public void accumulate(Bucket other) {
            for (int interval = 0; interval < intervalTotal; interval++) {
                this.intervalCounts[interval] += other.getIntervalCount(interval);
            }
            this.count += other.getCount();
        }
    }
}
