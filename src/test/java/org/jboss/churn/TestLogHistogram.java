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

import junit.framework.Assert;
import org.junit.Test;

/**
 * class to ensure that the LogHistogram class behaves as expected
 */
public class TestLogHistogram extends Assert
{
    /**
     * test simple bucket counting
     */
    @Test
    public void testSimpleBucketPlacement()
    {
        LogHistogram  histogram = new LogHistogram(3);
        // insert somevalues and check that the retrieved counts are as expected
        histogram.count(0);
        histogram.count(0);
        histogram.count(1);
        histogram.count(1);
        histogram.count(2);
        histogram.count(3);
        histogram.count(4);
        histogram.count(5);
        histogram.count(6);

        assertTrue(histogram.getLow(0) == 0);
        assertTrue(histogram.getHigh(0) == 1);
        assertTrue(histogram.getLow(1) == 2);
        assertTrue(histogram.getHigh(1) == 3);
        assertTrue(histogram.getLow(2) == 4);
        assertTrue(histogram.getHigh(2) == 7);

        assertTrue(histogram.getCount(0) == 4);
        assertTrue(histogram.getCount(1) == 2);
        assertTrue(histogram.getCount(2) == 3);

        histogram.printTo(System.out);
    }

    /**
     * test interval bucket counting with buckets divided into intervals of size 2
     */
    @Test
    public void testIntervalBucketPlacement2()
    {
        LogHistogram  histogram = new LogHistogram(3, true, 2);
        // insert somevalues and check that the retrieved counts are as expected
        histogram.count(0);
        histogram.count(0);
        histogram.count(1);
        histogram.count(1);
        histogram.count(2);
        histogram.count(3);
        histogram.count(4);
        histogram.count(5);
        histogram.count(6);

        assertTrue(histogram.getLow(0) == 0);
        assertTrue(histogram.getHigh(0) == 1);
        assertTrue(histogram.getLow(1) == 2);
        assertTrue(histogram.getHigh(1) == 3);
        assertTrue(histogram.getLow(2) == 4);
        assertTrue(histogram.getHigh(2) == 7);

        assertTrue(histogram.getCount(0) == 4);
        assertTrue(histogram.getCount(0, 0) == 4);
        assertTrue(histogram.getCount(0, 1) == 0);

        assertTrue(histogram.getCount(1) == 2);
        assertTrue(histogram.getCount(1, 0) == 1);
        assertTrue(histogram.getCount(1, 1) == 1);

        assertTrue(histogram.getCount(2) == 3);
        assertTrue(histogram.getCount(2, 0) == 2);
        assertTrue(histogram.getCount(2,1) == 1);

        histogram.printTo(System.out);
    }

    /**
     * test interval bucket counting with buckets divided into intervals of size 10
     */
    @Test
    public void testIntervalBucketPlacement10()
    {
        LogHistogram  histogram = new LogHistogram(11, true, 10);
        // insert some values and check that the retrieved counts are as expected
        // in particular the interval counts need to be accumulated
        // in the correct buckets taking into account rounding of the low
        // and high end of the bucket range. i.e. for bucket 2 the 1st
        // and 5th buckets will hold count 1 for value 2 and 1 for value 3
        // ad the other buckets will be empty.

        // 4 x bucket 0 interval 0
        histogram.count(0);
        histogram.count(0);
        histogram.count(1);
        histogram.count(1);
        // bucket 1 interval 0
        histogram.count(2);
        // bucket 1 interval 1
        histogram.count(3);
        // bucket 2 interval 0
        histogram.count(4);
        // bucket 2 interval 2
        histogram.count(5);
        // bucket 2 interval 5
        histogram.count(6);
        // bucket 10 interval 0
        histogram.count(1047);
        // bucket 10 interval 1
        histogram.count(1147);
        // bucket 10 interval 3
        histogram.count(1347);
        // 2 x bucket 10 interval 5
        histogram.count(1546);
        histogram.count(1547);
        // bucket 10 interval 9
        histogram.count(2047);

        histogram.printTo(System.out);

        assertTrue(histogram.getLow(0) == 0);
        assertTrue(histogram.getHigh(0) == 1);
        assertTrue(histogram.getLow(1) == 2);
        assertTrue(histogram.getHigh(1) == 3);
        assertTrue(histogram.getLow(2) == 4);
        assertTrue(histogram.getHigh(2) == 7);

        assertTrue(histogram.getCount(0) == 4);
        assertTrue(histogram.getCount(0, 0) == 4);
        assertTrue(histogram.getCount(0, 1) == 0);

        assertTrue(histogram.getCount(1) == 2);
        assertTrue(histogram.getCount(1, 0) == 1);
        assertTrue(histogram.getCount(1, 5) == 1);

        assertTrue(histogram.getCount(2) == 3);
        assertTrue(histogram.getCount(2, 0) == 1);
        assertTrue(histogram.getCount(2, 2) == 1);
        assertTrue(histogram.getCount(2, 5) == 1);

        assertTrue(histogram.getCount(10) == 6);
        assertTrue(histogram.getCount(10, 0) == 1);
        assertTrue(histogram.getCount(10, 1) == 1);
        assertTrue(histogram.getCount(10, 3) == 1);
        assertTrue(histogram.getCount(10, 5) == 2);
        assertTrue(histogram.getCount(10, 9) == 1);
        histogram.printTo(System.out);
    }
}
