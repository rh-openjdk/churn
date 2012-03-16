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

/**
 * WorkItems are used to define a linked structure which by default forms a "unicycle".
 */
public class WorkItem
{

    private String name;
    private WorkItem reference;
    private byte[][] data;

    WorkItem(String name, int count)
    {
        this(name, count, 250);
    }

    WorkItem(String name, int count, int size)
    {
        this.name = name;
        this.reference = this;
        this.data = new byte[count][];
        for (int i = 0; i < count; i++) {
            this.data[i] = new byte[size]; // default size implies about 256 bytes total per block
        }
    }

    String getName()
    {
        return name;
    }

    WorkItem getReference()
    {
        return reference;
    }

    byte[] getData(int i)
    {
        return data[i];
    }

    public int getBlockCount()
    {
        return data.length;
    }

    public int getBlockSize()
    {
        return (data.length > 0 ? data[0].length : 0);
    }

    void refer(WorkItem item)
    {
        reference = item;
    }

    void clear()
    {
        reference = this;
    }

    public void doWork(int initial, int computationCount) {
        // pretend to do something with this object
        byte[] block = data[0];
        int l = data.length;
        for (int i = 0; i < computationCount; i++) {
            byte value = (byte)(initial + i);
            block[i % l] ^= value;
        }
    }
}
