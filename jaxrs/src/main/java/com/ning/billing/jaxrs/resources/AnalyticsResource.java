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

package com.ning.billing.jaxrs.resources;

import java.util.Collection;
import java.util.UUID;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.ning.billing.account.api.Account;
import com.ning.billing.account.api.AccountApiException;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.analytics.api.BusinessSnapshot;
import com.ning.billing.analytics.api.TimeSeriesData;
import com.ning.billing.analytics.api.sanity.AnalyticsSanityApi;
import com.ning.billing.analytics.api.user.AnalyticsUserApi;
import com.ning.billing.jaxrs.json.AnalyticsSanityJson;
import com.ning.billing.jaxrs.json.BusinessSnapshotJson;
import com.ning.billing.jaxrs.json.TimeSeriesDataJson;
import com.ning.billing.jaxrs.util.Context;
import com.ning.billing.jaxrs.util.JaxrsUriBuilder;
import com.ning.billing.util.api.AuditUserApi;
import com.ning.billing.util.api.CustomFieldUserApi;
import com.ning.billing.util.api.TagUserApi;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.TenantContext;

import com.google.inject.Singleton;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Singleton
@Path(JaxrsResource.ANALYTICS_PATH)
public class AnalyticsResource extends JaxRsResourceBase {

    private final AccountUserApi accountUserApi;
    private final AnalyticsUserApi analyticsUserApi;
    private final AnalyticsSanityApi analyticsSanityApi;

    @Inject
    public AnalyticsResource(final AccountUserApi accountUserApi,
                             final AnalyticsUserApi analyticsUserApi,
                             final AnalyticsSanityApi analyticsSanityApi,
                             final JaxrsUriBuilder uriBuilder,
                             final TagUserApi tagUserApi,
                             final CustomFieldUserApi customFieldUserApi,
                             final AuditUserApi auditUserApi,
                             final Context context) {
        super(uriBuilder, tagUserApi, customFieldUserApi, auditUserApi, context);
        this.accountUserApi = accountUserApi;
        this.analyticsUserApi = analyticsUserApi;
        this.analyticsSanityApi = analyticsSanityApi;
    }

    @GET
    @Path("/sanity")
    @Produces(APPLICATION_JSON)
    public Response checkSanity(@javax.ws.rs.core.Context final HttpServletRequest request) {
        final TenantContext tenantContext = context.createContext(request);
        final Collection<UUID> checkEntitlement = analyticsSanityApi.checkAnalyticsInSyncWithEntitlement(tenantContext);
        final Collection<UUID> checkInvoice = analyticsSanityApi.checkAnalyticsInSyncWithInvoice(tenantContext);
        final Collection<UUID> checkPayment = analyticsSanityApi.checkAnalyticsInSyncWithPayment(tenantContext);
        final Collection<UUID> checkTag = analyticsSanityApi.checkAnalyticsInSyncWithTag(tenantContext);
        final Collection<UUID> checkConsistency = analyticsSanityApi.checkAnalyticsConsistency(tenantContext);

        final AnalyticsSanityJson json = new AnalyticsSanityJson(checkEntitlement,
                                                                 checkInvoice,
                                                                 checkPayment,
                                                                 checkTag,
                                                                 checkConsistency);
        return Response.status(Status.OK).entity(json).build();
    }

    @GET
    @Path("/{accountId:" + UUID_PATTERN + "}")
    @Produces(APPLICATION_JSON)
    public Response getBusinessSnapshotForAccount(@PathParam("accountId") final String accountId,
                                                  @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final TenantContext callContext = context.createContext(request);
        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), callContext);
        final BusinessSnapshot businessSnapshot = analyticsUserApi.getBusinessSnapshot(account, callContext);
        final BusinessSnapshotJson json = new BusinessSnapshotJson(businessSnapshot);
        return Response.status(Status.OK).entity(json).build();
    }

    @PUT
    @Path("/{accountId:" + UUID_PATTERN + "}")
    public Response rebuildAnalyticsForAccount(@PathParam("accountId") final String accountId,
                                               @HeaderParam(HDR_CREATED_BY) final String createdBy,
                                               @HeaderParam(HDR_REASON) final String reason,
                                               @HeaderParam(HDR_COMMENT) final String comment,
                                               @javax.ws.rs.core.Context final HttpServletRequest request) throws AccountApiException {
        final CallContext callContext = context.createContext(createdBy, reason, comment, request);
        final Account account = accountUserApi.getAccountById(UUID.fromString(accountId), callContext);
        analyticsUserApi.rebuildAnalyticsForAccount(account, callContext);
        return Response.status(Status.OK).build();
    }

    @GET
    @Path("/accountsCreatedOverTime")
    @Produces(APPLICATION_JSON)
    public Response getAccountsCreatedOverTime(@javax.ws.rs.core.Context final HttpServletRequest request) {
        final TimeSeriesData data = analyticsUserApi.getAccountsCreatedOverTime(context.createContext(request));
        final TimeSeriesDataJson json = new TimeSeriesDataJson(data);
        return Response.status(Status.OK).entity(json).build();
    }

    @GET
    @Path("/subscriptionsCreatedOverTime")
    @Produces(APPLICATION_JSON)
    public Response getSubscriptionsCreatedOverTime(@QueryParam("productType") final String productType,
                                                    @QueryParam("slug") final String slug,
                                                    @javax.ws.rs.core.Context final HttpServletRequest request) {
        final TimeSeriesData data = analyticsUserApi.getSubscriptionsCreatedOverTime(productType, slug, context.createContext(request));
        final TimeSeriesDataJson json = new TimeSeriesDataJson(data);
        return Response.status(Status.OK).entity(json).build();
    }
}
