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
 * You should have received a copy of the GNU Lesser General Pu102blic
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 *
 * @authors Andrew Dinn
 */

package org.jboss.churn;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Random;

/**
 * The Churn test runner is a main class which can be used to stress the memory
 * system of a JVM in order to exercise the garbage collector. It runs a
 * configurable number of threads which continuously allocates objects with a
 * mix of different sizes. Objects are retained and dropped to simulate
 * short-lived and long-lived data and threads occasionally purge parts of
 * their retained data to simulate application phase changes.
 */
public class TestRunner extends Thread
{
    /**
     * a long living collection of work items which is slowly updated with new items
     */
    private WorkItemMap longTermMap;

    /**
     * a per thread collection of work items which is regularly updated with new items
     */
    private WorkItemMap shortTermMap;

    /**
     * a histogram used to collect timings for each successive slice of the threads workload.
     * variations in the timings can be used to identify GC perturbations.
     */
    private LogHistogram logHistogram;

    /**
     * count of the number of bytes allocated by this thread when processing work items
     */
    private long allocationCount;

    /**
     * cost in bytes for allocating a new work item map
     */
    public static int workItemMapCost = 0;

    /**
     * cost in bytes for inserting or updating an item in a work map
     */
    public static int workItemInsertCost = 0;

    /**
     * cost in bytes for allocating a new work item
     */
    public static int workItemCost = 0;

    /**
     * the start index for the range of keys used by this thread to label work items
     */
    private int itemStart;

    /**
     * the odds that a thread will trash (nullify) all the long term map entries when it
     * finsihes an iteration over its subset of the short term/long term table
     */
    final private static int DUMP_LONG_TERM_ODDS = 100;

    /**
     * the odds that a work item will be promoted to the long term map rather than added to the short term map
     */
    final private static int PROMOTION_ODDS = 10;

    /**
     * the odds that a work item will be linked to another item rather than to itself
     */
    final private static int LINK_ODDS = 3;

    /**
     * the odds that a work item will hold on to a mega large object (1Mb)
     */
    final private static int MEGA_LARGE_OBJECT_ODDS = 1000;

    /**
     * the odds that a work item will hold on to a very large object 32Kb , 2 medium objects (2 * 1Kb) or
     * lots of small ones (blockCount * 32 bytes)
     * odds for a lareg object is 1 in LARGE_OBJECT_ODDS
     * odds for a medium object is 2 in LARGE_OBJECT_ODDS
     */
    final private static int LARGE_OBJECT_ODDS = 200;

    /**
     * number of blocks to allocate per work item. can be reset on command line using -blocks
     */
    private static int blockCount = 4;

    /**
     * number of worker threads. can be reset  on command line using -threads
     */
    private static int threadCount = 8;

    /**
     * total number of work items (measured in thousands) to hold in all per thread maps and also
     * the number of times to be held in the long term map. can be reset on command line using
     * -items
     */
    private static int itemTotal = 4 * 1000000;

    /**
     * number of map passes done be each thread during which it wil update its short term map and
     * possibly promote items to the long term map. can be reset on command line using -iterations.
     * disabled when 'duration' is set
     */
    private static int iterationCount = 200;

    /**
     * how long should churn run in seconds. the resulting time might differ as current iteration
     * is to be finished, but new iteration after the 'duration' won't be started.
     * disabled by default. when set to some positive value, it overwrites 'iterationCount'.
     */
    private static int duration = 0;

    /**
     * number of computations performed per allocation. this is used to slow down the allocation
     * rate. It also indirectly determines the sample interval for timing the progress of a given
     * thread through its work load. can be set on the commandline using -computations
     */
    private static int computationCount = 32;

    /**
     * number of allocations which are performed before the clock time is sampled to measure
     * the elapsed time for a slice of work. By configuring a suitable value for this parameter
     * the average interval between time readings can be madeto lie in the range 1-10 milliseconds.
     * This allows variations in the time it takes to to run individual slices to be displayed in
     * a histogram graph. Note that the timing is dependent upon the setting of both this value and
     * the computation count value (as well as on the performance of the JVM being used to execute
     * the test). can be set on the commandline using -slices
     */
    private static int sliceCount = 100;

    /**
     * control determining whether to yield the processor between timeslice samples. a negative
     * value means don't yield, zero means yield and a positive value means sleep for that
     * number of milliseconds. can be set on the commandline using -yieldMSecs
     */
    private static int yieldMSecCount = -1;

    /**
     * number of items to be processed by any given worker. n.b. this potentially ignores
     * itemTotal mod threadCount items but since itemTotal >> threadCount this is no big deal
     */

    private static int itemCount = itemTotal / threadCount;

    /**
     * identifier for the worker thread
     */
    private int id;

    /**
     * main method allowing test to be run. command line options are as follows:
     * <ul>
     *     <li>-blocks B -- number of 32 byte blocks allocated per work item (default 4)</li>
     *     <li>-items I -- total number of work items to retain in map / (1000) (default 4000)</li>
     *     <li>-threads T -- number of worker threads to run in parallel (default 8)</li>
     *     <li>-iterations N -- number of passes over map either replacing or promoting entries (defaults to 200)</li>
     *     <li>-computations C -- number of compute/write operations to each work items data block (defaults to 32)</li>
     *     <li>-slices S -- number work item allocations/computations which constitute each timed 'task' (defaults to 100).</li>
     *     <li>-yield Y -- if 0 then a thread will yield after processing each slice if positive i twill sleep for Y msecs
     *     (defaults to -1)</li>
     * </ul>
     *
     * The defaults mean that the N thread short term maps will hold a little over 4Gb of data as, eventually,
     * will the long term maps. Each of the 8 threads will populate and then iteratively
     * update the contents of its short term map with ~ 1/2 Gb of data and this data will slowly be promoted
     * into its long term map. So, the default settings should fit into and rapidly start to stress a JVM with
     * 12 - 16 Gb of heap for any of the standard OpenJDK GCs (your mileage may vary).
     * <p/>
     * You can control the allocate to compute ratio by configuring C. You can also control how long a sample 'task'
     * takes to complete on average by configuring S and C (where a task coprises S work item allocate/compute
     * operations). Variations above this average time in th eoutput histogram wil be down to pauses introduced
     * by the GC or, possibly, the OS.
     *
     * The program prints a totoal execution time and a histogram displaying the task times on a logartihmic scale.
     * New gen GC pauses should mean that some of the task execution times will be in the 10s to 100s msecs range.
     * Old gen GC pauses should mean that some of the task execution times will be in the 1s to 10s msecs range.
     * Of course your mileage may vary depending upon number and type of cores, heap sizes etc.
     *
     * @param args
     */
    public static void main(String[] args)
    {
        processArgs(args);

        /**
         * identify costs for allocating various objects
         */
        calibrate();

        TestRunner[] runners = new TestRunner[threadCount];
        for (int i = 0; i < threadCount; i++) {
            runners[i] = new TestRunner(i);
        }
        long start = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            runners[i].start();
        }
        for (int i = 0; i < threadCount; i++) {
            try {
                runners[i].join();
            } catch (InterruptedException e) {
                System.out.println("failed to join runner[" + i + "]");
                e.printStackTrace();
            }
        }
        long end = System.currentTimeMillis();
        System.out.println("Elapsed time " + (((end - start) * 1.0) / 1000) + " seconds for " + threadCount + " threads");
        System.out.println();
        long allocated = 0;
        if (threadCount > 1) {
            LogHistogram total = new LogHistogram(true, 10);
            for (int i= 0; i < threadCount; i++) {
                LogHistogram next = runners[i].getHistogram();
                total.accumulate(next);
                long threadAllocated = runners[i].getAllocationCount();
                allocated += threadAllocated;
                System.out.println("Thread Allocated" + threadAllocated / (1024 * 1024) + " MBs");
                System.out.println("Thread " + i + " Histogram");
                next.printTo(System.out);
            }
            System.out.println("Total Allocated" + allocated / (1024 * 1024) + " MBs");
            System.out.println("Accumulated Histogram");
            total.printTo(System.out);
        } else {
            allocated += runners[0].getAllocationCount();
            System.out.println("Total Allocated" + allocated / (1024 * 1024) + " MBs");
            System.out.println("Accumulated Histogram");
            runners[0].getHistogram().printTo(System.out);
        }
    }

    private static void processArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            try {
                if (args[i].equals("-blocks") && i + 1 < args.length) {
                    i++;
                    blockCount = Integer.valueOf(args[i]);
                    if (blockCount <= 0) {
                        usage(2, args[i]);
                    }
                } else if (args[i].equals("-items") && i + 1 < args.length) {
                    i++;
                    itemTotal = Integer.valueOf(args[i]);
                } else if (args[i].equals("-threads") && i + 1 < args.length) {
                    i++;
                    threadCount = Integer.valueOf(args[i]);
                    if (threadCount <= 0 || threadCount > 64) {
                        usage(4, args[i]);
                    }
                    /*
                    // thread count must be a power of 2
                    if ((threadCount & (threadCount - 1)) != 0) {
                        usage(4, args[i]);
                    }
                    */
                } else if (args[i].equals("-iterations") && i + 1 < args.length) {
                    i++;
                    iterationCount = Integer.valueOf(args[i]);
                    if (iterationCount <= 0) {
                        usage(5, args[i]);
                    }
                } else if (args[i].equals("-duration")) {
                    i++;
                    duration = Integer.valueOf(args[i]);
                    if (duration <= 0) {
                        usage(10, args[i]);
                    }
                } else if (args[i].equals("-computations") && i + 1 < args.length) {
                    i++;
                    computationCount = Integer.valueOf(args[i]);
                    if (computationCount <= 0) {
                        usage(6, args[i]);
                    }
                } else if (args[i].equals("-slices") && i + 1 < args.length) {
                    i++;
                    sliceCount = Integer.valueOf(args[i]);
                    if (sliceCount <= 0) {
                        usage(7, args[i]);
                    }
                } else if (args[i].equals("-yieldMSecs") && i + 1 < args.length) {
                    i++;
                    yieldMSecCount = Integer.valueOf(args[i]);
                    if (yieldMSecCount < -1) {
                        usage(8, args[i]);
                    }
                } else {
                    usage(9, args[i]);
                }
            } catch (NumberFormatException e) {
                usage(1, args[i]);
            }
        }

        // recompute derived data

        itemCount =  itemTotal / threadCount;

    }

    /**
     * identify sizings for the various objects we will allocate during the test run
     */
    private static void calibrate() {
        System.out.println("calibrating");
        MemoryMXBean mxbean = ManagementFactory.getMemoryMXBean();
        MemoryUsage memoryUsage = mxbean.getHeapMemoryUsage();
        long estimatedHeapMB = (int) (memoryUsage.getMax() / (1024 * 1024));
        int objectCount = 10000;
        Object[] handle = new Object[objectCount];

        // estimate size of WorkItemMap
        System.gc();
        long initialUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        for (int i = 0; i < objectCount; i++) {
            handle[i] = new WorkItemMap();
        }
        long bytesUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() - initialUsage;

        workItemMapCost = (int)(bytesUsed/objectCount);

        for (int i = 0; i < objectCount; i++) {
            handle[i] = null;
        }

        // compute cost of adding an item to the map
        WorkItemMap map = new WorkItemMap();
        String name = "item 0";
        WorkItem item = new WorkItem(name, 0, 0);

        System.gc();

        initialUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        for (int i = 0; i < objectCount; i++) {
            name = "item " + i;
            map.put(name, item);
        }
        bytesUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() - initialUsage;

        workItemInsertCost = (int)(bytesUsed/objectCount);

        // compute size of WorkItem


        initialUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        for (int i = 0; i < objectCount; i++) {
            handle[i] = new WorkItem(name, 0, 0);
        }
        bytesUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() - initialUsage;

        workItemCost = (int)(bytesUsed/objectCount);
    }

    /**
     * count allocation overhead for adding a new item map
     */
    private void countMapAllocate()
    {
        allocationCount += workItemMapCost;
    }

    /**
     * count allocation overhead for adding an item to an item map or updating an item entry
     */
    private void countItemInsert()
    {
        allocationCount += workItemInsertCost;
    }

    /**
     * count allocation overhead for allocating this item
     */
    private void countItemAllocate(WorkItem item)
    {
        allocationCount += workItemCost + (item.getBlockCount() * item.getBlockSize());
    }

    private static void usage(int i, String extra) {
        switch(i) {
            case 1:
                System.out.println("invalid number format " + extra);
                break;
            case 2:
                System.out.println("invalid block count " + extra);
                break;
            case 3:
                System.out.println("invalid item total " + extra);
                break;
            case 4:
                System.out.println("invalid thread count " + extra);
                break;
            case 5:
                System.out.println("invalid iteration count " + extra);
                break;
            case 6:
                System.out.println("invalid computation count " + extra);
                break;
            case 7:
                System.out.println("invalid slice count " + extra);
                break;
            case 8:
                System.out.println("invalid yield time " + extra);
                break;
            case 9:
                System.out.println("invalid argument " + extra);
                break;
            case 10:
                System.out.println("invalid duration count " + extra);
                break;
        }
        System.out.println("usage TestRunner [-blocks B] [-items I] [-threads T] [-iterations N | -duration D] [-computations C] [-slices S] [-yieldMSecs Y");
        System.exit(i);
    }

    public TestRunner(int id)
    {
        this.id  = id;
        this.longTermMap = new WorkItemMap();
        this.shortTermMap = new WorkItemMap();
        this.itemStart = id * itemCount;
        this.logHistogram = new LogHistogram(true, 10);
        this.allocationCount = 0;
    }

    public void run()
    {
        doWork();
    }

    public void doWork()
    {
        System.out.println("thread " + id + " : start");
        Random random = new Random(itemStart);

        // first fill the short term workmap with the required instances so all references can be resolved

        for (int i = 0; i < itemCount; i++) {
            int idx = itemStart + i;
            String name = "item " + idx;
            WorkItem item = new WorkItem(name, blockCount);
            shortTermMap.put(name, item);
        }

        // now create some chains with a low probability of them reaching any serious length

        System.out.println("thread " + id + " : link");

        for (int i = 0; i < itemCount; i++) {
	    if (random.nextInt(LINK_ODDS) == 0) {
                int idx = itemStart + i;
                int linkIdx = itemStart + random.nextInt(itemCount);
                String name = "item " + idx;
                String linkName = "item " + linkIdx;
                WorkItem item = shortTermMap.get(name);
                WorkItem linkItem = shortTermMap.get(linkName);
                // a new item references itself in a direct cycle. we want to link this
                // item to the front of a chain but in doing so we don't want to create
                // any indirect cycles. that allows us to detect end of chain using condition
                //   item.getReference() == item
                // now if we start off with only direct cycles then the only way we can
                // create an indirect cycle is by adding an item to an existing chain which
                // already contains the item. otherwise we will just create a non-cyclic chain
                // terminated by a direct cycle.
                WorkItem next = linkItem;
                WorkItem reference = linkItem.getReference();
                // progress down the chain checking each link
                while (next != item && next != reference) {
                    next = reference;
                    reference = next.getReference();
                }
                if (next != item) {
                    // item is not in chain so we can safely add it to the start
                    item.refer(linkItem);
                }
            }
        }

        System.out.println("thread " + id + " : iterate");
        // iterate over the collection repeatedly either replacing or promoting each item

        int slice = 0;
        long currentTime = System.currentTimeMillis();

        LoopCondition loopCond = createLoopCondition();
        int iterationCounter;
        for (iterationCounter = 0; loopCond.check(iterationCounter); iterationCounter++) {
            // across each 10 successive iterations we bias item block sizes from
            // 50% to 150% of the nominal size, making it all the more likely we run
            // into mature space fragmentation issues
            int sizeBias = 6 + (iterationCounter % 10);
            for (int i = 0; i < itemCount; i++) {
                doOneItem(random, i, sizeBias);
                // increment the slicecounter and see if we need to collect a timing
                slice = (slice + 1) % sliceCount;
                if (slice == 0) {
                    long newTime= System.currentTimeMillis();
                    long diff = newTime - currentTime;
                    logHistogram.count(diff);
                    if (yieldMSecCount >= 0) {
                        try {
                            if (yieldMSecCount == 0) {
                                Thread.yield();
                            } else {
                                Thread.sleep(yieldMSecCount);
                            }
                        } catch (InterruptedException e) {
                            // ignore
                        }
                        // don't count time yielded or sleeping as part of next task time
                        newTime= System.currentTimeMillis();
                    }
                    currentTime = newTime;
                }
            }

            // we want to purge the map every now and then so we dump a whole load of old data
            // for new data. n.b. we scale the odds by itemTotal thousands so that we purge after
            // this thread has allocated a fixed amount rather than every time round the loop
            // (the amount allocated every time round the loop is proportional to itemTotalThousands)

            if (random.nextInt(DUMP_LONG_TERM_ODDS) <= itemTotal / 1000) {
                // System.out.println(id + " : (" + iteration + ") purge[" + itemStart + "->" + (itemStart + itemCount - 1) + "]");
                longTermMap = new WorkItemMap();
                countMapAllocate();
            }

            // System.out.println("thread " + id + " : loop " + (iteration + 1));
        }
        System.out.println("thread " + id + " : done [" + iterationCounter + "] iterations");
        System.out.println("thread " + id + " : end");
    }

    private LoopCondition createLoopCondition() {
        final long startTime = System.currentTimeMillis();
        // if duration is set, use that
        if (duration > 0) {
            return new LoopCondition() {
                @Override
                public boolean check(int counter) {
                    long currentDuration = System.currentTimeMillis() - startTime;
                    return currentDuration / 1000 <= duration;
                }
            };
        } else {
            return new LoopCondition() {
                @Override
                public boolean check(int iteration) {
                    return iteration < iterationCount;
                }
            };
        }
    }

    /**
     * the inner loop core operation which allocates an item, inserts it in the short term map and,
     * potentially, promotes the old item into the long term map
     * @param random a source of random values
     * @param i an offset from the threads item start index identifying both the short term and long term
     * work item which may need to be modified.
     * @param bias a value between 6 and 15 used to scale the size of the data blocks hung off this
     * item from 50% to 140% of the nominal size
     */
    private void doOneItem(Random random, int i, int bias) {
        int idx = itemStart + i;
        String name = "item " + idx;
        WorkItem item = shortTermMap.get(name);
        // we promote the short term item if the long term map is empty
        // we also promote it at random but with a skew for certain elements to vary their lifetime
        if (longTermMap.get(name) == null) {
            longTermMap.put(name, item);
            countMapAllocate();
        } else {
            // we increase the multiplier for a specific 1 in 8 items so they tend to live longer
            int multiplier = ((i & 7) == 0 ? 10 : 1);
            int ratio = PROMOTION_ODDS * multiplier;
            // we vary the odds randomly per item but ensure that they average to 1 in PROMOTION_ODDS
            int randomValue = random.nextInt(2 * ratio);
            int cutoff = random.nextInt(3); // odds are uniformly either 1/2N, 2/2N or 3/2N
            if (randomValue <= cutoff) {
                // promote this item into the long term map -- deleting any existing entry
                longTermMap.put(name, item);
                // TODO hmm, assumes replace cost same as add cost!
                countMapAllocate();
            }
        }
        // now create a new version of this item and maybe link it into a chain
        // note that we will never create a cycle

        int size_randomizer = random.nextInt(MEGA_LARGE_OBJECT_ODDS);
        if (size_randomizer == 0) {
            // ok, create a 1 Mb object
            item = new WorkItem(name, 1, 1024 * 1024 * bias / 10);
        } else {
            size_randomizer = random.nextInt(LARGE_OBJECT_ODDS);
            if (size_randomizer == 0) {
                // one very large object 32K
                item = new WorkItem(name, 1, 32 * 1024  * bias / 10);
            } else if (size_randomizer < 4) {
                // 2 medium objects 2K each
                item = new WorkItem(name, 2, 1024 * bias / 10);
            } else {
                // N small objects about 32 bytes each
                item = new WorkItem(name, blockCount, 32 * bias / 10);
            }
        }
        countItemAllocate(item);
        if (random.nextInt(LINK_ODDS) == 0) {
            int linkIdx = itemStart + random.nextInt(itemCount);
            String linkName = "item " + linkIdx;
            WorkItem linkItem = shortTermMap.get(linkName);
            item.refer(linkItem);
        }
        shortTermMap.put(name, item);

        item.doWork(i, computationCount);
        // TODO hmm, assumes replace cost same as add cost!
        countMapAllocate();
    }

    public LogHistogram getHistogram()
    {
        return logHistogram;
    }

    public long getAllocationCount()
    {
        return allocationCount;
    }

    private interface LoopCondition {
        boolean check(int counter);
    }
}
