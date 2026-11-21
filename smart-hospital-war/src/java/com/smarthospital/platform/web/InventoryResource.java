package com.smarthospital.platform.web;

import com.smarthospital.clinical.inventory.InventoryServiceLocal;
import com.smarthospital.platform.iam.AuthPrincipal;
import com.smarthospital.platform.iam.AuthorizationService;
import com.smarthospital.shared.error.AppException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** General inventory: warehouses, items, stock, transfers, counts, purchasing (FR-INV-001..010). */
@Path("/inventory")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class InventoryResource {

    @Inject
    private InventoryServiceLocal inventory;

    @Inject
    private AuthorizationService authz;

    @Context
    private HttpServletRequest request;

    // ------------------------------------------------------------------
    // Warehouses
    // ------------------------------------------------------------------

    @GET
    @Path("/warehouses")
    public Map<String, Object> warehouses(@QueryParam("q") String query,
                                          @QueryParam("activeOnly") @DefaultValue("false")
                                          boolean activeOnly) {
        authz.require(principal(), "INV:READ");
        return inventory.listWarehouses(query, activeOnly);
    }

    @POST
    @Path("/warehouses")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createWarehouse(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:WRITE");
        Map<String, Object> created = inventory.createWarehouse(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/inventory/warehouses/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @PATCH
    @Path("/warehouses/{publicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateWarehouse(@PathParam("publicId") String publicId,
                                               Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:WRITE");
        return inventory.updateWarehouse(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    @GET
    @Path("/items")
    public Map<String, Object> items(@QueryParam("q") String query,
                                     @QueryParam("category") String category,
                                     @QueryParam("activeOnly") @DefaultValue("false")
                                     boolean activeOnly,
                                     @QueryParam("page") @DefaultValue("0") int page,
                                     @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "INV:READ");
        return inventory.listItems(query, category, activeOnly, page, size);
    }

    @GET
    @Path("/items/{publicId}")
    public Map<String, Object> item(@PathParam("publicId") String publicId) {
        authz.require(principal(), "INV:READ");
        return inventory.item(publicId);
    }

    @POST
    @Path("/items")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createItem(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:WRITE");
        Map<String, Object> created = inventory.createItem(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/inventory/items/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @PATCH
    @Path("/items/{publicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateItem(@PathParam("publicId") String publicId,
                                          Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:WRITE");
        return inventory.updateItem(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Suppliers
    // ------------------------------------------------------------------

    @GET
    @Path("/suppliers")
    public Map<String, Object> suppliers(@QueryParam("q") String query,
                                         @QueryParam("activeOnly") @DefaultValue("false")
                                         boolean activeOnly) {
        authz.require(principal(), "INV:READ");
        return inventory.listSuppliers(query, activeOnly);
    }

    @POST
    @Path("/suppliers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createSupplier(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:SUPPLIER");
        Map<String, Object> created = inventory.createSupplier(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/inventory/suppliers/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @PATCH
    @Path("/suppliers/{publicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateSupplier(@PathParam("publicId") String publicId,
                                              Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:SUPPLIER");
        return inventory.updateSupplier(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Batches, issues, write-offs and transfers
    // ------------------------------------------------------------------

    @GET
    @Path("/batches")
    public Map<String, Object> batches(@QueryParam("q") String query,
                                       @QueryParam("item") String item,
                                       @QueryParam("warehouse") String warehouse,
                                       @QueryParam("status") String status,
                                       @QueryParam("expiryBefore") String expiryBefore,
                                       @QueryParam("page") @DefaultValue("0") int page,
                                       @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "INV:READ");
        return inventory.listBatches(query, item, warehouse, status, date(expiryBefore),
                page, size);
    }

    @POST
    @Path("/batches")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receiveBatch(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:RECEIVE");
        Map<String, Object> created = inventory.receiveBatch(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/inventory/batches/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @POST
    @Path("/issues")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response issue(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:ISSUE");
        return Response.status(Response.Status.CREATED)
                .entity(inventory.issue(body, actor, WebCookies.clientIp(request)))
                .build();
    }

    @POST
    @Path("/write-offs")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response writeOff(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:WRITE_OFF");
        return Response.status(Response.Status.CREATED)
                .entity(inventory.writeOff(body, actor, WebCookies.clientIp(request)))
                .build();
    }

    @POST
    @Path("/transfers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response transfer(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:TRANSFER");
        Map<String, Object> created = inventory.transfer(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/inventory/transfers/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @GET
    @Path("/transfers")
    public Map<String, Object> transfers(@QueryParam("page") @DefaultValue("0") int page,
                                         @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "INV:READ");
        return inventory.listTransfers(page, size);
    }

    @GET
    @Path("/movements")
    public Map<String, Object> movements(@QueryParam("item") String item,
                                         @QueryParam("warehouse") String warehouse,
                                         @QueryParam("type") String type,
                                         @QueryParam("from") String from,
                                         @QueryParam("to") String to,
                                         @QueryParam("page") @DefaultValue("0") int page,
                                         @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "INV:READ");
        return inventory.listMovements(item, warehouse, type, date(from), date(to), page, size);
    }

    @GET
    @Path("/alerts")
    public Map<String, Object> alerts() {
        authz.require(principal(), "INV:READ");
        return inventory.alerts();
    }

    // ------------------------------------------------------------------
    // Stock counts
    // ------------------------------------------------------------------

    @GET
    @Path("/counts")
    public Map<String, Object> counts(@QueryParam("status") String status,
                                      @QueryParam("page") @DefaultValue("0") int page,
                                      @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "INV:READ");
        return inventory.listCounts(status, page, size);
    }

    @GET
    @Path("/counts/{publicId}")
    public Map<String, Object> count(@PathParam("publicId") String publicId) {
        authz.require(principal(), "INV:READ");
        return inventory.count(publicId);
    }

    @POST
    @Path("/counts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createCount(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:COUNT");
        Map<String, Object> created = inventory.createCount(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/inventory/counts/" + created.get("publicId"))
                .entity(created)
                .build();
    }

    @PATCH
    @Path("/counts/{publicId}/lines/{linePublicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> updateCountLine(@PathParam("publicId") String publicId,
                                               @PathParam("linePublicId") String linePublicId,
                                               Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:COUNT");
        return inventory.updateCountLine(publicId, linePublicId, body, actor);
    }

    @POST
    @Path("/counts/{publicId}/post")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> postCount(@PathParam("publicId") String publicId,
                                         Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:COUNT");
        return inventory.postCount(publicId, body, actor, WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Purchase requests and orders
    // ------------------------------------------------------------------

    @GET
    @Path("/purchase-requests")
    public Map<String, Object> purchaseRequests(@QueryParam("status") String status,
                                                @QueryParam("page") @DefaultValue("0") int page,
                                                @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "INV:READ");
        return inventory.listPurchaseRequests(status, page, size);
    }

    @POST
    @Path("/purchase-requests")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createPurchaseRequest(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:REQUEST");
        Map<String, Object> created = inventory.createPurchaseRequest(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/inventory/purchase-requests/"
                        + created.get("publicId"))
                .entity(created)
                .build();
    }

    @POST
    @Path("/purchase-requests/{publicId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> approvePurchaseRequest(@PathParam("publicId") String publicId,
                                                      Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:APPROVE");
        return inventory.approvePurchaseRequest(publicId, body, actor,
                WebCookies.clientIp(request));
    }

    @GET
    @Path("/purchase-orders")
    public Map<String, Object> purchaseOrders(@QueryParam("status") String status,
                                              @QueryParam("page") @DefaultValue("0") int page,
                                              @QueryParam("size") @DefaultValue("20") int size) {
        authz.require(principal(), "INV:READ");
        return inventory.listPurchaseOrders(status, page, size);
    }

    @GET
    @Path("/purchase-orders/{publicId}")
    public Map<String, Object> purchaseOrder(@PathParam("publicId") String publicId) {
        authz.require(principal(), "INV:READ");
        return inventory.purchaseOrder(publicId);
    }

    @POST
    @Path("/purchase-orders")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createPurchaseOrder(Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:PO_CREATE");
        Map<String, Object> created = inventory.createPurchaseOrder(body, actor,
                WebCookies.clientIp(request));
        return Response.status(Response.Status.CREATED)
                .header("Location", "/api/v1/inventory/purchase-orders/"
                        + created.get("publicId"))
                .entity(created)
                .build();
    }

    @POST
    @Path("/purchase-orders/{publicId}/receive")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> receivePurchaseOrder(@PathParam("publicId") String publicId,
                                                    Map<String, Object> body) {
        AuthPrincipal actor = principal();
        authz.require(actor, "INV:RECEIVE");
        return inventory.receivePurchaseOrder(publicId, body, actor,
                WebCookies.clientIp(request));
    }

    // ------------------------------------------------------------------
    // Reports
    // ------------------------------------------------------------------

    @GET
    @Path("/reports/valuation")
    public Map<String, Object> valuation(@QueryParam("warehouse") String warehouse) {
        authz.require(principal(), "INV:READ");
        return inventory.valuation(warehouse);
    }

    @GET
    @Path("/reports/consumption")
    public Map<String, Object> consumption(@QueryParam("from") String from,
                                           @QueryParam("to") String to,
                                           @QueryParam("item") String item) {
        authz.require(principal(), "INV:READ");
        return inventory.consumption(date(from), date(to), item);
    }

    private AuthPrincipal principal() {
        Object value = request.getAttribute(WebCookies.PRINCIPAL_ATTRIBUTE);
        if (value instanceof AuthPrincipal principal) {
            return principal;
        }
        throw AppException.unauthorized("AUTH_REQUIRED",
                "يلزم تسجيل الدخول.",
                "Authentication required.");
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw AppException.badRequest("VALIDATION_FAILED",
                    "صيغة التاريخ غير صحيحة. استخدم YYYY-MM-DD.",
                    "Invalid date format. Use YYYY-MM-DD.");
        }
    }
}
