/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.subscription.api.user;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.billing.catalog.api.BillingActionPolicy;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Catalog;
import com.ning.billing.catalog.api.CatalogApiException;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.PlanPhase;
import com.ning.billing.catalog.api.PlanPhaseSpecifier;
import com.ning.billing.catalog.api.PriceList;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.clock.Clock;
import com.ning.billing.entitlement.api.BlockingState;
import com.ning.billing.subscription.api.SubscriptionApiService;
import com.ning.billing.subscription.api.SubscriptionBase;
import com.ning.billing.subscription.api.SubscriptionTransitionType;
import com.ning.billing.subscription.api.user.SubscriptionTransitionDataIterator.Kind;
import com.ning.billing.subscription.api.user.SubscriptionTransitionDataIterator.Order;
import com.ning.billing.subscription.api.user.SubscriptionTransitionDataIterator.TimeLimit;
import com.ning.billing.subscription.api.user.SubscriptionTransitionDataIterator.Visibility;
import com.ning.billing.subscription.events.SubscriptionEvent;
import com.ning.billing.subscription.events.SubscriptionEvent.EventType;
import com.ning.billing.subscription.events.phase.PhaseEvent;
import com.ning.billing.subscription.events.user.ApiEvent;
import com.ning.billing.subscription.events.user.ApiEventType;
import com.ning.billing.subscription.exceptions.SubscriptionError;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.entity.EntityBase;

public class SubscriptionData extends EntityBase implements SubscriptionBase {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionData.class);

    private final Clock clock;
    private final SubscriptionApiService apiService;

    //
    // Final subscription fields
    //
    private final UUID bundleId;
    private final DateTime alignStartDate;
    private final DateTime bundleStartDate;
    private final ProductCategory category;

    //
    // Those can be modified through non User APIs, and a new Subscription
    // object would be created
    //
    private final long activeVersion;
    private final DateTime chargedThroughDate;
    private final DateTime paidThroughDate;

    //
    // User APIs (create, change, cancel,...) will recompute those each time,
    // so the user holding that subscription object get the correct state when
    // the call completes
    //
    private LinkedList<SubscriptionBaseTransition> transitions;

    // Low level events are ONLY used for Repair APIs
    protected List<SubscriptionEvent> events;


    public List<SubscriptionEvent> getEvents() {
        return events;
    }

    // Transient object never returned at the API
    public SubscriptionData(final SubscriptionBuilder builder) {
        this(builder, null, null);
    }

    public SubscriptionData(final SubscriptionBuilder builder, @Nullable final SubscriptionApiService apiService, @Nullable final Clock clock) {
        super(builder.getId(), builder.getCreatedDate(), builder.getUpdatedDate());
        this.apiService = apiService;
        this.clock = clock;
        this.bundleId = builder.getBundleId();
        this.alignStartDate = builder.getAlignStartDate();
        this.bundleStartDate = builder.getBundleStartDate();
        this.category = builder.getCategory();
        this.activeVersion = builder.getActiveVersion();
        this.chargedThroughDate = builder.getChargedThroughDate();
        this.paidThroughDate = builder.getPaidThroughDate();
    }

    // Used for API to make sure we have a clock and an apiService set before we return the object
    public SubscriptionData(final SubscriptionData internalSubscription, final SubscriptionApiService apiService, final Clock clock) {
        super(internalSubscription.getId(), internalSubscription.getCreatedDate(), internalSubscription.getUpdatedDate());
        this.apiService = apiService;
        this.clock = clock;
        this.bundleId = internalSubscription.getBundleId();
        this.alignStartDate = internalSubscription.getAlignStartDate();
        this.bundleStartDate = internalSubscription.getBundleStartDate();
        this.category = internalSubscription.getCategory();
        this.activeVersion = internalSubscription.getActiveVersion();
        this.chargedThroughDate = internalSubscription.getChargedThroughDate();
        this.paidThroughDate = internalSubscription.getPaidThroughDate();
        this.transitions = new LinkedList<SubscriptionBaseTransition>(internalSubscription.getAllTransitions());
        this.events = internalSubscription.getEvents();
    }

    @Override
    public UUID getBundleId() {
        return bundleId;
    }

    @Override
    public DateTime getStartDate() {
        return transitions.get(0).getEffectiveTransitionTime();
    }

    @Override
    public SubscriptionState getState() {
        return (getPreviousTransition() == null) ? null
                                                 : getPreviousTransition().getNextState();
    }

    @Override
    public SubscriptionSourceType getSourceType() {
        if (transitions == null) {
            return null;
        }
        final SubscriptionBaseTransitionData initialTransition = (SubscriptionBaseTransitionData) transitions.get(0);
        switch (initialTransition.getApiEventType()) {
            case MIGRATE_BILLING:
            case MIGRATE_ENTITLEMENT:
                return SubscriptionSourceType.MIGRATED;
            case TRANSFER:
                return SubscriptionSourceType.TRANSFERED;
            default:
                return SubscriptionSourceType.NATIVE;
        }
    }

    @Override
    public PlanPhase getCurrentPhase() {
        return (getPreviousTransition() == null) ? null
                                                 : getPreviousTransition().getNextPhase();
    }

    @Override
    public Plan getCurrentPlan() {
        return (getPreviousTransition() == null) ? null
                                                 : getPreviousTransition().getNextPlan();
    }

    @Override
    public PriceList getCurrentPriceList() {
        return (getPreviousTransition() == null) ? null :
               getPreviousTransition().getNextPriceList();

    }

    @Override
    public DateTime getEndDate() {
        final SubscriptionBaseTransition latestTransition = getPreviousTransition();
        if (latestTransition.getNextState() == SubscriptionState.CANCELLED) {
            return latestTransition.getEffectiveTransitionTime();
        }
        return null;
    }

    @Override
    public DateTime getFutureEndDate() {
        if (transitions == null) {
            return null;
        }

        final SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(
                clock, transitions, Order.ASC_FROM_PAST, Kind.SUBSCRIPTION,
                Visibility.ALL, TimeLimit.FUTURE_ONLY);
        while (it.hasNext()) {
            final SubscriptionBaseTransition cur = it.next();
            if (cur.getTransitionType() == SubscriptionTransitionType.CANCEL) {
                return cur.getEffectiveTransitionTime();
            }
        }
        return null;
    }

    @Override
    public boolean cancel(final DateTime requestedDate, final CallContext context) throws SubscriptionBaseApiException {
        return apiService.cancel(this, requestedDate, context);
    }

    @Override
    public boolean cancelWithPolicy(final DateTime requestedDate, final BillingActionPolicy policy, final CallContext context) throws SubscriptionBaseApiException {
        return apiService.cancelWithPolicy(this, requestedDate, policy, context);
    }

    @Override
    public boolean uncancel(final CallContext context)
            throws SubscriptionBaseApiException {
        return apiService.uncancel(this, context);
    }

    @Override
    public boolean changePlan(final String productName, final BillingPeriod term, final String priceList,
                              final DateTime requestedDate, final CallContext context) throws SubscriptionBaseApiException {
        return apiService.changePlan(this, productName, term, priceList, requestedDate, context);
    }

    @Override
    public boolean changePlanWithPolicy(final String productName, final BillingPeriod term, final String priceList,
                                        final DateTime requestedDate, final BillingActionPolicy policy, final CallContext context) throws SubscriptionBaseApiException {
        return apiService.changePlanWithPolicy(this, productName, term, priceList, requestedDate, policy, context);
    }

    @Override
    public boolean recreate(final PlanPhaseSpecifier spec, final DateTime requestedDate,
                            final CallContext context) throws SubscriptionBaseApiException {
        return apiService.recreatePlan(this, spec, requestedDate, context);
    }

    @Override
    public BlockingState getBlockingState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SubscriptionBaseTransition getPendingTransition() {
        if (transitions == null) {
            return null;
        }
        final SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(
                clock, transitions, Order.ASC_FROM_PAST, Kind.SUBSCRIPTION,
                Visibility.ALL, TimeLimit.FUTURE_ONLY);
        return it.hasNext() ? it.next() : null;
    }

    @Override
    public String getLastActiveProductName() {
        if (getState() == SubscriptionState.CANCELLED) {
            final SubscriptionBaseTransition data = getPreviousTransition();
            return data.getPreviousPlan().getProduct().getName();
        } else {
            return getCurrentPlan().getProduct().getName();
        }
    }

    @Override
    public String getLastActivePriceListName() {
        if (getState() == SubscriptionState.CANCELLED) {
            final SubscriptionBaseTransition data = getPreviousTransition();
            return data.getPreviousPriceList().getName();
        } else {
            return getCurrentPriceList().getName();
        }
    }

    @Override
    public String getLastActiveCategoryName() {
        if (getState() == SubscriptionState.CANCELLED) {
            final SubscriptionBaseTransition data = getPreviousTransition();
            return data.getPreviousPlan().getProduct().getCategory().name();
        } else {
            return getCurrentPlan().getProduct().getCategory().name();
        }
    }

    @Override
    public Plan getLastActivePlan() {
        if (getState() == SubscriptionState.CANCELLED) {
            final SubscriptionBaseTransition data = getPreviousTransition();
            return data.getPreviousPlan();
        } else {
            return getCurrentPlan();
        }
    }

    @Override
    public String getLastActiveBillingPeriod() {
        if (getState() == SubscriptionState.CANCELLED) {
            final SubscriptionBaseTransition data = getPreviousTransition();
            return data.getPreviousPlan().getBillingPeriod().name();
        } else {
            return getCurrentPlan().getBillingPeriod().name();
        }
    }

    @Override
    public SubscriptionBaseTransition getPreviousTransition() {
        if (transitions == null) {
            return null;
        }
        final SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(
                clock, transitions, Order.DESC_FROM_FUTURE, Kind.SUBSCRIPTION,
                Visibility.FROM_DISK_ONLY, TimeLimit.PAST_OR_PRESENT_ONLY);
        return it.hasNext() ? it.next() : null;
    }

    @Override
    public ProductCategory getCategory() {
        return category;
    }

    public DateTime getBundleStartDate() {
        return bundleStartDate;
    }

    @Override
    public DateTime getChargedThroughDate() {
        return chargedThroughDate;
    }

    @Override
    public DateTime getPaidThroughDate() {
        return paidThroughDate;
    }

    @Override
    public List<SubscriptionBaseTransition> getAllTransitions() {
        if (transitions == null) {
            return Collections.emptyList();
        }
        final List<SubscriptionBaseTransition> result = new ArrayList<SubscriptionBaseTransition>();
        final SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(clock, transitions, Order.ASC_FROM_PAST, Kind.ALL, Visibility.ALL, TimeLimit.ALL);
        while (it.hasNext()) {
            result.add(it.next());
        }
        return result;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                 + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final SubscriptionData other = (SubscriptionData) obj;
        if (id == null) {
            if (other.id != null) {
                return false;
            }
        } else if (!id.equals(other.id)) {
            return false;
        }
        return true;
    }


    public SubscriptionBaseTransitionData getTransitionFromEvent(final SubscriptionEvent event, final int seqId) {
        if (transitions == null || event == null) {
            return null;
        }
        SubscriptionBaseTransitionData prev = null;
        for (final SubscriptionBaseTransition cur : transitions) {
            final SubscriptionBaseTransitionData curData = (SubscriptionBaseTransitionData) cur;
            if (curData.getId().equals(event.getId())) {

                final SubscriptionBaseTransitionData withSeq = new SubscriptionBaseTransitionData(curData, seqId);
                return withSeq;
            }
            if (curData.getTotalOrdering() < event.getTotalOrdering()) {
                prev = curData;
            }
        }
        // Since UNCANCEL are not part of the transitions, we compute a new 'UNCANCEL' transition based on the event right before that UNCANCEL
        // This is used to be able to send a bus event for uncancellation
        if (prev != null && event.getType() == EventType.API_USER && ((ApiEvent) event).getEventType() == ApiEventType.UNCANCEL) {
            final SubscriptionBaseTransitionData withSeq = new SubscriptionBaseTransitionData((SubscriptionBaseTransitionData) prev, EventType.API_USER, ApiEventType.UNCANCEL, seqId);
            return withSeq;
        }
        return null;
    }

    public DateTime getAlignStartDate() {
        return alignStartDate;
    }

    public long getLastEventOrderedId() {
        final SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(
                clock, transitions, Order.DESC_FROM_FUTURE, Kind.SUBSCRIPTION,
                Visibility.FROM_DISK_ONLY, TimeLimit.ALL);
        return it.hasNext() ? ((SubscriptionBaseTransitionData) it.next()).getTotalOrdering() : -1L;
    }

    public long getActiveVersion() {
        return activeVersion;
    }

    public List<SubscriptionBaseTransition> getBillingTransitions() {

        if (transitions == null) {
            return Collections.emptyList();
        }
        final List<SubscriptionBaseTransition> result = new ArrayList<SubscriptionBaseTransition>();
        final SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(
                clock, transitions, Order.ASC_FROM_PAST, Kind.BILLING,
                Visibility.ALL, TimeLimit.ALL);
        // Remove anything prior to first CREATE or MIGRATE_BILLING
        boolean foundInitialEvent = false;
        while (it.hasNext()) {
            final SubscriptionBaseTransitionData curTransition = (SubscriptionBaseTransitionData) it.next();
            if (!foundInitialEvent) {
                foundInitialEvent = curTransition.getEventType() == EventType.API_USER &&
                                    (curTransition.getApiEventType() == ApiEventType.CREATE ||
                                     curTransition.getApiEventType() == ApiEventType.MIGRATE_BILLING ||
                                     curTransition.getApiEventType() == ApiEventType.TRANSFER);
            }
            if (foundInitialEvent) {
                result.add(curTransition);
            }
        }
        return result;
    }


    public SubscriptionBaseTransitionData getInitialTransitionForCurrentPlan() {
        if (transitions == null) {
            throw new SubscriptionError(String.format("No transitions for subscription %s", getId()));
        }

        final SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(clock,
                                                                                             transitions,
                                                                                             Order.DESC_FROM_FUTURE,
                                                                                             Kind.SUBSCRIPTION,
                                                                                             Visibility.ALL,
                                                                                             TimeLimit.PAST_OR_PRESENT_ONLY);

        while (it.hasNext()) {
            final SubscriptionBaseTransitionData cur = (SubscriptionBaseTransitionData) it.next();
            if (cur.getTransitionType() == SubscriptionTransitionType.CREATE
                || cur.getTransitionType() == SubscriptionTransitionType.RE_CREATE
                || cur.getTransitionType() == SubscriptionTransitionType.TRANSFER
                || cur.getTransitionType() == SubscriptionTransitionType.CHANGE
                || cur.getTransitionType() == SubscriptionTransitionType.MIGRATE_ENTITLEMENT) {
                return cur;
            }
        }

        throw new SubscriptionError(String.format("Failed to find InitialTransitionForCurrentPlan id = %s", getId()));
    }

    public boolean isSubscriptionFutureCancelled() {
        return getFutureEndDate() != null;
    }

    public DateTime getPlanChangeEffectiveDate(final BillingActionPolicy policy,
                                               final DateTime requestedDate) {

        if (policy == BillingActionPolicy.IMMEDIATE) {
            return requestedDate;
        }
        if (policy != BillingActionPolicy.END_OF_TERM) {
            throw new SubscriptionError(String.format(
                    "Unexpected policy type %s", policy.toString()));
        }

        if (chargedThroughDate == null) {
            return requestedDate;
        } else {
            return chargedThroughDate.isBefore(requestedDate) ? requestedDate
                                                              : chargedThroughDate;
        }
    }

    public DateTime getCurrentPhaseStart() {

        if (transitions == null) {
            throw new SubscriptionError(String.format(
                    "No transitions for subscription %s", getId()));
        }
        final SubscriptionTransitionDataIterator it = new SubscriptionTransitionDataIterator(
                clock, transitions, Order.DESC_FROM_FUTURE, Kind.SUBSCRIPTION,
                Visibility.ALL, TimeLimit.PAST_OR_PRESENT_ONLY);
        while (it.hasNext()) {
            final SubscriptionBaseTransitionData cur = (SubscriptionBaseTransitionData) it.next();

            if (cur.getTransitionType() == SubscriptionTransitionType.PHASE
                || cur.getTransitionType() == SubscriptionTransitionType.TRANSFER
                || cur.getTransitionType() == SubscriptionTransitionType.CREATE
                || cur.getTransitionType() == SubscriptionTransitionType.RE_CREATE
                || cur.getTransitionType() == SubscriptionTransitionType.CHANGE
                || cur.getTransitionType() == SubscriptionTransitionType.MIGRATE_ENTITLEMENT) {
                return cur.getEffectiveTransitionTime();
            }
        }
        throw new SubscriptionError(String.format(
                "Failed to find CurrentPhaseStart id = %s", getId().toString()));
    }

    public void rebuildTransitions(final List<SubscriptionEvent> inputEvents, final Catalog catalog) {

        if (inputEvents == null) {
            return;
        }

        this.events = inputEvents;

        UUID nextUserToken = null;

        UUID nextEventId = null;
        DateTime nextCreatedDate = null;
        SubscriptionState nextState = null;
        String nextPlanName = null;
        String nextPhaseName = null;
        String nextPriceListName = null;

        UUID prevEventId = null;
        DateTime prevCreatedDate = null;
        SubscriptionState previousState = null;
        PriceList previousPriceList = null;
        Plan previousPlan = null;
        PlanPhase previousPhase = null;

        transitions = new LinkedList<SubscriptionBaseTransition>();

        for (final SubscriptionEvent cur : inputEvents) {

            if (!cur.isActive() || cur.getActiveVersion() < activeVersion) {
                continue;
            }

            ApiEventType apiEventType = null;

            boolean isFromDisk = true;

            nextEventId = cur.getId();
            nextCreatedDate = cur.getCreatedDate();

            switch (cur.getType()) {

                case PHASE:
                    final PhaseEvent phaseEV = (PhaseEvent) cur;
                    nextPhaseName = phaseEV.getPhase();
                    break;

                case API_USER:
                    final ApiEvent userEV = (ApiEvent) cur;
                    apiEventType = userEV.getEventType();
                    isFromDisk = userEV.isFromDisk();

                    switch (apiEventType) {
                        case TRANSFER:
                        case MIGRATE_BILLING:
                        case MIGRATE_ENTITLEMENT:
                        case CREATE:
                        case RE_CREATE:
                            prevEventId = null;
                            prevCreatedDate = null;
                            previousState = null;
                            previousPlan = null;
                            previousPhase = null;
                            previousPriceList = null;
                            nextState = SubscriptionState.ACTIVE;
                            nextPlanName = userEV.getEventPlan();
                            nextPhaseName = userEV.getEventPlanPhase();
                            nextPriceListName = userEV.getPriceList();
                            break;
                        case CHANGE:
                            nextPlanName = userEV.getEventPlan();
                            nextPhaseName = userEV.getEventPlanPhase();
                            nextPriceListName = userEV.getPriceList();
                            break;
                        case CANCEL:
                            nextState = SubscriptionState.CANCELLED;
                            nextPlanName = null;
                            nextPhaseName = null;
                            break;
                        case UNCANCEL:
                        default:
                            throw new SubscriptionError(String.format(
                                    "Unexpected UserEvent type = %s", userEV
                                    .getEventType().toString()));
                    }
                    break;
                default:
                    throw new SubscriptionError(String.format(
                            "Unexpected Event type = %s", cur.getType()));
            }

            Plan nextPlan = null;
            PlanPhase nextPhase = null;
            PriceList nextPriceList = null;

            try {
                nextPlan = (nextPlanName != null) ? catalog.findPlan(nextPlanName, cur.getRequestedDate(), getAlignStartDate()) : null;
                nextPhase = (nextPhaseName != null) ? catalog.findPhase(nextPhaseName, cur.getRequestedDate(), getAlignStartDate()) : null;
                nextPriceList = (nextPriceListName != null) ? catalog.findPriceList(nextPriceListName, cur.getRequestedDate()) : null;
            } catch (CatalogApiException e) {
                log.error(String.format("Failed to build transition for subscription %s", id), e);
            }

            final SubscriptionBaseTransitionData transition = new SubscriptionBaseTransitionData(
                    cur.getId(), id, bundleId, cur.getType(), apiEventType,
                    cur.getRequestedDate(), cur.getEffectiveDate(),
                    prevEventId, prevCreatedDate,
                    previousState, previousPlan, previousPhase,
                    previousPriceList,
                    nextEventId, nextCreatedDate,
                    nextState, nextPlan, nextPhase,
                    nextPriceList, cur.getTotalOrdering(), nextUserToken,
                    isFromDisk);

            transitions.add(transition);

            previousState = nextState;
            previousPlan = nextPlan;
            previousPhase = nextPhase;
            previousPriceList = nextPriceList;
            prevEventId = nextEventId;
            prevCreatedDate = nextCreatedDate;

        }
    }
}
