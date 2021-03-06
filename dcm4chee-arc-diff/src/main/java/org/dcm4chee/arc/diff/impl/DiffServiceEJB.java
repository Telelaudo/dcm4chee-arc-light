/*
 * **** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015-2019
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * **** END LICENSE BLOCK *****
 *
 */

package org.dcm4chee.arc.diff.impl;

import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.criteria.Expression;
import javax.persistence.Tuple;

import org.dcm4che3.data.Attributes;
import org.dcm4chee.arc.diff.*;
import org.dcm4chee.arc.entity.*;
import org.dcm4chee.arc.event.QueueMessageEvent;
import org.dcm4chee.arc.qmgt.IllegalTaskStateException;
import org.dcm4chee.arc.qmgt.QueueManager;
import org.dcm4chee.arc.qmgt.QueueSizeLimitExceededException;
import org.dcm4chee.arc.query.util.MatchTask;
import org.dcm4chee.arc.query.util.QueryBuilder;
import org.dcm4chee.arc.query.util.TaskQueryParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.ObjectMessage;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.*;
import javax.persistence.metamodel.SingularAttribute;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Mar 2018
 */
@Stateless
public class DiffServiceEJB {

    private static final Logger LOG = LoggerFactory.getLogger(DiffServiceEJB.class);

    @PersistenceContext(unitName="dcm4chee-arc")
    private EntityManager em;

    @Inject
    private QueueManager queueManager;

    public void scheduleDiffTask(DiffContext ctx) throws QueueSizeLimitExceededException {
        try {
            ObjectMessage msg = queueManager.createObjectMessage(0);
            msg.setStringProperty("LocalAET", ctx.getLocalAE().getAETitle());
            msg.setStringProperty("PrimaryAET", ctx.getPrimaryAE().getAETitle());
            msg.setStringProperty("SecondaryAET", ctx.getSecondaryAE().getAETitle());
            msg.setIntProperty("Priority", ctx.priority());
            msg.setStringProperty("QueryString", ctx.getQueryString());
            if (ctx.getHttpServletRequestInfo() != null)
                ctx.getHttpServletRequestInfo().copyTo(msg);
            QueueMessage queueMessage = queueManager.scheduleMessage(DiffService.QUEUE_NAME, msg,
                    Message.DEFAULT_PRIORITY, ctx.getBatchID(), 0L);
            createDiffTask(ctx, queueMessage);
        } catch (JMSException e) {
            throw QueueMessage.toJMSRuntimeException(e);
        }
    }

    private void createDiffTask(DiffContext ctx, QueueMessage queueMessage) {
        DiffTask task = new DiffTask();
        task.setLocalAET(ctx.getLocalAE().getAETitle());
        task.setPrimaryAET(ctx.getPrimaryAE().getAETitle());
        task.setSecondaryAET(ctx.getSecondaryAE().getAETitle());
        task.setQueryString(ctx.getQueryString());
        task.setCheckMissing(ctx.isCheckMissing());
        task.setCheckDifferent(ctx.isCheckDifferent());
        task.setCompareFields(ctx.getCompareFields());
        task.setQueueMessage(queueMessage);
        em.persist(task);
    }

    public void resetDiffTask(DiffTask diffTask) {
        diffTask = em.find(DiffTask.class, diffTask.getPk());
        diffTask.reset();
        diffTask.getDiffTaskAttributes().forEach(entity -> em.remove(entity));
    }

    public void addDiffTaskAttributes(DiffTask diffTask, Attributes attrs) {
        diffTask = em.find(DiffTask.class, diffTask.getPk());
        if (diffTask != null) {
            diffTask.getDiffTaskAttributes().add(new AttributesBlob(attrs));
        }
    }

    public void updateDiffTask(DiffTask diffTask, DiffSCU diffSCU) {
        diffTask = em.find(DiffTask.class, diffTask.getPk());
        if (diffTask != null) {
            diffTask.setMatches(diffSCU.matches());
            diffTask.setMissing(diffSCU.missing());
            diffTask.setDifferent(diffSCU.different());
        }
    }

    public DiffTask getDiffTask(long taskPK) {
        return em.find(DiffTask.class, taskPK);
    }

    public boolean deleteDiffTask(Long pk, QueueMessageEvent queueEvent) {
        DiffTask task = em.find(DiffTask.class, pk);
        if (task == null)
            return false;

        queueManager.deleteTask(task.getQueueMessage().getMessageID(), queueEvent);
        LOG.info("Delete {}", task);
        return true;
    }

    public int deleteTasks(
            TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam, int deleteTasksFetchSize) {
        List<String> referencedQueueMsgIDs = em.createQuery(
                select(QueueMessage_.messageID, queueTaskQueryParam, diffTaskQueryParam))
                .setMaxResults(deleteTasksFetchSize)
                .getResultList();

        referencedQueueMsgIDs.forEach(queueMsgID -> queueManager.deleteTask(queueMsgID, null));
        return referencedQueueMsgIDs.size();
    }

    public List<String> listDistinctDeviceNames(TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam) {
        return em.createQuery(
                select(QueueMessage_.deviceName, queueTaskQueryParam, diffTaskQueryParam).distinct(true))
                .getResultList();
    }

    public List<String> listDiffTaskQueueMsgIDs(
            TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam, int limit) {
        return em.createQuery(select(QueueMessage_.messageID, queueTaskQueryParam, diffTaskQueryParam))
                .setMaxResults(limit)
                .getResultList();
    }

    private CriteriaQuery<String> select(SingularAttribute<QueueMessage, String> attribute,
            TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<String> q = cb.createQuery(String.class);
        Root<DiffTask> diffTask = q.from(DiffTask.class);
        From<DiffTask, QueueMessage> queueMsg = diffTask.join(DiffTask_.queueMessage);
        List<Predicate> predicates = new MatchTask(cb).diffPredicates(
                queueMsg, diffTask, queueTaskQueryParam, diffTaskQueryParam);
        if (!predicates.isEmpty())
            q.where(predicates.toArray(new Predicate[0]));
        return q.select(queueMsg.get(attribute));
    }

    public long diffTasksOfBatch(String batchID) {
        return em.createNamedQuery(DiffTask.COUNT_BY_BATCH_ID, Long.class)
                .setParameter(1, batchID)
                .getSingleResult();
    }

    public boolean cancelDiffTask(Long pk, QueueMessageEvent queueEvent) throws IllegalTaskStateException {
        DiffTask task = em.find(DiffTask.class, pk);
        if (task == null)
            return false;

        QueueMessage queueMessage = task.getQueueMessage();
        if (queueMessage == null)
            throw new IllegalTaskStateException("Cannot cancel Task with status: 'TO SCHEDULE'");

        queueManager.cancelTask(queueMessage.getMessageID(), queueEvent);
        LOG.info("Cancel {}", task);
        return true;
    }

    public long cancelDiffTasks(TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam) {
        return queueManager.cancelDiffTasks(queueTaskQueryParam, diffTaskQueryParam);
    }

    public void rescheduleDiffTask(Long pk, QueueMessageEvent queueEvent) {
        DiffTask task = em.find(DiffTask.class, pk);
        if (task == null)
            return;

        LOG.info("Reschedule {}", task);
        rescheduleDiffTask(task.getQueueMessage().getMessageID(), queueEvent);
    }

    public void rescheduleDiffTask(String msgId, QueueMessageEvent queueEvent) {
        queueManager.rescheduleTask(msgId, DiffService.QUEUE_NAME, queueEvent);
    }

    public String findDeviceNameByPk(Long pk) {
        try {
            return em.createNamedQuery(DiffTask.FIND_DEVICE_BY_PK, String.class)
                    .setParameter(1, pk)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    public List<byte[]> getDiffTaskAttributes(DiffTask diffTask, int offset, int limit) {
        return em.createNamedQuery(DiffTask.FIND_ATTRS_BY_PK, byte[].class)
                .setParameter(1, diffTask.getPk())
                .setFirstResult(offset)
                .setMaxResults(limit)
                .getResultList();
    }

    public List<byte[]> getDiffTaskAttributes(
            TaskQueryParam queueBatchQueryParam, TaskQueryParam diffBatchQueryParam, int offset, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<byte[]> q = cb.createQuery(byte[].class);
        Root<DiffTask> diffTask = q.from(DiffTask.class);
        From<DiffTask, QueueMessage> queueMsg = diffTask.join(DiffTask_.queueMessage);

        List<Predicate> predicates = new MatchTask(cb).diffBatchPredicates(
                queueMsg, diffTask, queueBatchQueryParam, diffBatchQueryParam);
        if (!predicates.isEmpty())
            q.where(predicates.toArray(new Predicate[0]));

        CollectionJoin<DiffTask, AttributesBlob> attrsBlobs = diffTask.join(DiffTask_.diffTaskAttributes);
        TypedQuery<byte[]> query = em.createQuery(q.select(attrsBlobs.get(AttributesBlob_.encodedAttributes)));
        if (offset > 0)
            query.setFirstResult(offset);
        if (limit > 0)
            query.setMaxResults(limit);
        return query.getResultList();

    }

    public List<DiffBatch> listDiffBatches(
            TaskQueryParam queueBatchQueryParam, TaskQueryParam diffBatchQueryParam, int offset, int limit) {
        ListDiffBatches listDiffBatches = new ListDiffBatches(queueBatchQueryParam, diffBatchQueryParam);
        TypedQuery<Tuple> query = em.createQuery(listDiffBatches.query);
        if (offset > 0)
            query.setFirstResult(offset);
        if (limit > 0)
            query.setMaxResults(limit);

        return query.getResultStream().map(listDiffBatches::toDiffBatch).collect(Collectors.toList());
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public Iterator<DiffTask> listDiffTasks(
            TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam, int offset, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<DiffTask> q = cb.createQuery(DiffTask.class);
        Root<DiffTask> diffTask = q.from(DiffTask.class);
        From<DiffTask, QueueMessage> queueMsg = diffTask.join(DiffTask_.queueMessage);
        MatchTask matchTask = new MatchTask(cb);
        List<Predicate> predicates = matchTask.diffPredicates(queueMsg, diffTask, queueTaskQueryParam, diffTaskQueryParam);
        if (!predicates.isEmpty())
            q.where(predicates.toArray(new Predicate[0]));
        if (diffTaskQueryParam.getOrderBy() != null)
            q.orderBy(matchTask.diffTaskOrder(diffTaskQueryParam.getOrderBy(), diffTask));
        TypedQuery<DiffTask> query = em.createQuery(q);
        if (offset > 0)
            query.setFirstResult(offset);
        if (limit > 0)
            query.setMaxResults(limit);
        return query.getResultStream().iterator();
    }

    public long countTasks(TaskQueryParam queueTaskQueryParam, TaskQueryParam diffTaskQueryParam) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<DiffTask> diffTask = q.from(DiffTask.class);
        From<DiffTask, QueueMessage> queueMsg = diffTask.join(DiffTask_.queueMessage);
        List<Predicate> predicates = new MatchTask(cb).diffPredicates(queueMsg, diffTask, queueTaskQueryParam, diffTaskQueryParam);
        if (!predicates.isEmpty())
            q.where(predicates.toArray(new Predicate[0]));
        return QueryBuilder.unbox(em.createQuery(q.select(cb.count(diffTask))).getSingleResult(), 0L);
    }

    private class ListDiffBatches {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<Tuple> query = cb.createTupleQuery();
        final Root<DiffTask> diffTask = query.from(DiffTask.class);
        final From<DiffTask, QueueMessage> queueMsg = diffTask.join(DiffTask_.queueMessage);
        final Expression<Date> minProcessingStartTime = cb.least(queueMsg.get(QueueMessage_.processingStartTime));
        final Expression<Date> maxProcessingStartTime = cb.greatest(queueMsg.get(QueueMessage_.processingStartTime));
        final Expression<Date> minProcessingEndTime = cb.least(queueMsg.get(QueueMessage_.processingEndTime));
        final Expression<Date> maxProcessingEndTime = cb.greatest(queueMsg.get(QueueMessage_.processingEndTime));
        final Expression<Date> minScheduledTime = cb.least(queueMsg.get(QueueMessage_.scheduledTime));
        final Expression<Date> maxScheduledTime = cb.greatest(queueMsg.get(QueueMessage_.scheduledTime));
        final Expression<Date> minCreatedTime = cb.least(diffTask.get(DiffTask_.createdTime));
        final Expression<Date> maxCreatedTime = cb.greatest(diffTask.get(DiffTask_.createdTime));
        final Expression<Date> minUpdatedTime = cb.least(diffTask.get(DiffTask_.updatedTime));
        final Expression<Date> maxUpdatedTime = cb.greatest(diffTask.get(DiffTask_.updatedTime));
        final Expression<Long> matches = cb.sumAsLong((diffTask.get(DiffTask_.matches)));
        final Expression<Long> missing = cb.sumAsLong(diffTask.get(DiffTask_.missing));
        final Expression<Long> different = cb.sumAsLong(diffTask.get(DiffTask_.different));
        final Path<String> batchid = queueMsg.get(QueueMessage_.batchID);

        ListDiffBatches(TaskQueryParam queueBatchQueryParam, TaskQueryParam diffBatchQueryParam) {
            query.multiselect(minProcessingStartTime, maxProcessingStartTime,
                    minProcessingEndTime, maxProcessingEndTime,
                    minScheduledTime, maxScheduledTime,
                    minCreatedTime, maxCreatedTime,
                    minUpdatedTime, maxUpdatedTime,
                    matches, missing, different, batchid);
            query.groupBy(queueMsg.get(QueueMessage_.batchID));
            MatchTask matchTask = new MatchTask(cb);
            List<Predicate> predicates = matchTask.diffBatchPredicates(
                    queueMsg, diffTask, queueBatchQueryParam, diffBatchQueryParam);
            if (!predicates.isEmpty())
                query.where(predicates.toArray(new Predicate[0]));
            if (diffBatchQueryParam.getOrderBy() != null)
                query.orderBy(matchTask.diffBatchOrder(diffBatchQueryParam.getOrderBy(), diffTask));
        }

        DiffBatch toDiffBatch(Tuple tuple) {
            String batchID = tuple.get(batchid);
            DiffBatch diffBatch = new DiffBatch(batchID);
            diffBatch.setProcessingStartTimeRange(
                    tuple.get(maxProcessingStartTime),
                    tuple.get(maxProcessingStartTime));
            diffBatch.setProcessingEndTimeRange(
                    tuple.get(minProcessingEndTime),
                    tuple.get(maxProcessingEndTime));
            diffBatch.setScheduledTimeRange(
                    tuple.get(minScheduledTime),
                    tuple.get(maxScheduledTime));
            diffBatch.setCreatedTimeRange(
                    tuple.get(minCreatedTime),
                    tuple.get(maxCreatedTime));
            diffBatch.setUpdatedTimeRange(
                    tuple.get(minUpdatedTime),
                    tuple.get(maxUpdatedTime));

            diffBatch.setMatches(tuple.get(matches));
            diffBatch.setMissing(tuple.get(missing));
            diffBatch.setDifferent(tuple.get(different));

            diffBatch.setDeviceNames(
                    em.createNamedQuery(DiffTask.FIND_DEVICE_BY_BATCH_ID, String.class)
                            .setParameter(1, batchID)
                            .getResultList());
            diffBatch.setLocalAETs(
                    em.createNamedQuery(DiffTask.FIND_LOCAL_AET_BY_BATCH_ID, String.class)
                            .setParameter(1, batchID)
                            .getResultList());
            diffBatch.setPrimaryAETs(
                    em.createNamedQuery(DiffTask.FIND_PRIMARY_AET_BY_BATCH_ID, String.class)
                            .setParameter(1, batchID)
                            .getResultList());
            diffBatch.setSecondaryAETs(
                    em.createNamedQuery(DiffTask.FIND_SECONDARY_AET_BY_BATCH_ID, String.class)
                            .setParameter(1, batchID)
                            .getResultList());
            diffBatch.setComparefields(
                    em.createNamedQuery(DiffTask.FIND_COMPARE_FIELDS_BY_BATCH_ID, String.class)
                            .setParameter(1, batchID)
                            .getResultList());
            diffBatch.setCheckMissing(
                    em.createNamedQuery(DiffTask.FIND_CHECK_MISSING_BY_BATCH_ID, Boolean.class)
                            .setParameter(1, batchID)
                            .getResultList());
            diffBatch.setCheckDifferent(
                    em.createNamedQuery(DiffTask.FIND_CHECK_DIFFERENT_BY_BATCH_ID, Boolean.class)
                            .setParameter(1, batchID)
                            .getResultList());
            diffBatch.setCompleted(
                    em.createNamedQuery(DiffTask.COUNT_BY_BATCH_ID_AND_STATUS, Long.class)
                            .setParameter(1, batchID)
                            .setParameter(2, QueueMessage.Status.COMPLETED)
                            .getSingleResult());
            diffBatch.setCanceled(
                    em.createNamedQuery(DiffTask.COUNT_BY_BATCH_ID_AND_STATUS, Long.class)
                            .setParameter(1, batchID)
                            .setParameter(2, QueueMessage.Status.CANCELED)
                            .getSingleResult());
            diffBatch.setWarning(
                    em.createNamedQuery(DiffTask.COUNT_BY_BATCH_ID_AND_STATUS, Long.class)
                            .setParameter(1, batchID)
                            .setParameter(2, QueueMessage.Status.WARNING)
                            .getSingleResult());
            diffBatch.setFailed(
                    em.createNamedQuery(DiffTask.COUNT_BY_BATCH_ID_AND_STATUS, Long.class)
                            .setParameter(1, batchID)
                            .setParameter(2, QueueMessage.Status.FAILED)
                            .getSingleResult());
            diffBatch.setScheduled(
                    em.createNamedQuery(DiffTask.COUNT_BY_BATCH_ID_AND_STATUS, Long.class)
                            .setParameter(1, batchID)
                            .setParameter(2, QueueMessage.Status.SCHEDULED)
                            .getSingleResult());
            diffBatch.setInProcess(
                    em.createNamedQuery(DiffTask.COUNT_BY_BATCH_ID_AND_STATUS, Long.class)
                            .setParameter(1, batchID)
                            .setParameter(2, QueueMessage.Status.IN_PROCESS)
                            .getSingleResult());
            return diffBatch;
        }
    }
}
