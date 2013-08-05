/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.voltcore.logging.VoltLogger;
import org.voltdb.exceptions.TransactionRestartException;

import org.voltdb.messaging.FragmentResponseMessage;
import org.voltdb.messaging.FragmentTaskMessage;

public class MpTransactionTaskQueue extends TransactionTaskQueue
{
    protected static final VoltLogger hostLog = new VoltLogger("HOST");

    private TransactionTask m_currentTask = null;

    MpTransactionTaskQueue(SiteTaskerQueue queue)
    {
        super(queue);
    }

    /**
     * If necessary, stick this task in the backlog.
     * Many network threads may be racing to reach here, synchronize to
     * serialize queue order
     * @param task
     * @return true if this task was stored, false if not
     */
    synchronized boolean offer(TransactionTask task)
    {
        Iv2Trace.logTransactionTaskQueueOffer(task);
        m_backlog.addLast(task);
        taskQueueOffer();
        return true;
    }

    // repair is used by MPI repair to inject a repair task into the
    // SiteTaskerQueue.  Before it does this, it unblocks the MP transaction
    // that may be running in the Site thread and causes it to rollback by
    // faking an unsuccessful FragmentResponseMessage.
    synchronized void repair(SiteTasker task, List<Long> masters, Map<Integer, Long> partitionMasters)
    {
        // At the MPI's TransactionTaskQueue, we know that there can only be
        // one transaction in the SiteTaskerQueue at a time, because the
        // TransactionTaskQueue will only release one MP transaction to the
        // SiteTaskerQueue at a time.  So, when we offer this repair task, we
        // know it will be the next thing to run once we poison the current
        // TXN.
        m_taskQueue.offer(task);
        Iterator<TransactionTask> iter = m_backlog.iterator();
        if (iter.hasNext()) {
            MpProcedureTask next = (MpProcedureTask)iter.next();
            next.doRestart(masters, partitionMasters);
            // get head
            // Only the MPI's TransactionTaskQueue is ever called in this way, so we know
            // that the TransactionTasks we pull out of it have to be MP transactions, so this
            // cast is safe
            MpTransactionState txn = (MpTransactionState)next.getTransactionState();
            // inject poison pill
            FragmentTaskMessage dummy = new FragmentTaskMessage(0L, 0L, 0L, 0L, false, false, false);
            FragmentResponseMessage poison =
                new FragmentResponseMessage(dummy, 0L); // Don't care about source HSID here
            // Provide a TransactionRestartException which will be converted
            // into a ClientResponse.RESTART, so that the MpProcedureTask can
            // detect the restart and take the appropriate actions.
            TransactionRestartException restart = new TransactionRestartException(
                    "Transaction being restarted due to fault recovery or shutdown.", next.getTxnId());
            poison.setStatus(FragmentResponseMessage.UNEXPECTED_ERROR, restart);
            txn.offerReceivedFragmentResponse(poison);
            // Now, iterate through the rest of the data structure and update the partition masters
            // for all MpProcedureTasks not at the head of the TransactionTaskQueue
            while (iter.hasNext())
            {
                next = (MpProcedureTask)iter.next();
                next.updateMasters(masters, partitionMasters);
            }
        }
    }

    private boolean taskQueueOffer()
    {
        boolean retval = false;
        if (m_currentTask == null) {
            if (!m_backlog.isEmpty()) {
                TransactionTask task = m_backlog.getFirst();
                m_currentTask = task;
                Iv2Trace.logSiteTaskerQueueOffer(task);
                m_taskQueue.offer(task);
                retval = true;
            }
        }
        return retval;
    }

    /**
     * Try to offer as many runnable Tasks to the SiteTaskerQueue as possible.
     * @return the number of TransactionTasks queued to the SiteTaskerQueue
     */
    synchronized int flush()
    {
        int offered = 0;
        if (m_backlog.isEmpty() || !m_backlog.getFirst().getTransactionState().isDone()) {
            return offered;
        }
        m_backlog.removeFirst();
        m_currentTask = null;
        if (taskQueueOffer()) {
            ++offered;
        }
        return offered;
    }

    /**
     * Restart the current task at the head of the queue.  This will be called
     * instead of flush by the currently blocking MP transaction in the event a
     * restart is necessary.
     */
    synchronized void restart()
    {
        m_currentTask = null;
        taskQueueOffer();
    }

    /**
     * How many Tasks are un-runnable?
     * @return
     */
    synchronized int size()
    {
        return m_backlog.size();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("MpTransactionTaskQueue:").append("\n");
        sb.append("\tSIZE: ").append(size());
        if (!m_backlog.isEmpty()) {
            sb.append("\tHEAD: ").append(m_backlog.getFirst());
        }
        return sb.toString();
    }
}