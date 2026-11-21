package com.smarthospital.clinical.inventory;

import com.smarthospital.platform.iam.AuthPrincipal;

import java.time.LocalDate;
import java.util.Map;

/** Local view of the inventory service (FR-INV-001..010). */
public interface InventoryServiceLocal {

    Map<String, Object> listWarehouses(String query, boolean activeOnly);

    Map<String, Object> createWarehouse(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> updateWarehouse(String publicId, Map<String, Object> body,
                                        AuthPrincipal actor, String ip);

    Map<String, Object> listItems(String query, String category, boolean activeOnly,
                                  int page, int size);

    Map<String, Object> item(String publicId);

    Map<String, Object> createItem(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> updateItem(String publicId, Map<String, Object> body,
                                   AuthPrincipal actor, String ip);

    Map<String, Object> listSuppliers(String query, boolean activeOnly);

    Map<String, Object> createSupplier(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> updateSupplier(String publicId, Map<String, Object> body,
                                       AuthPrincipal actor, String ip);

    Map<String, Object> listBatches(String query, String itemPublicId,
                                    String warehousePublicId, String status,
                                    LocalDate expiryBefore, int page, int size);

    Map<String, Object> receiveBatch(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> issue(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> writeOff(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> transfer(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> listTransfers(int page, int size);

    Map<String, Object> listMovements(String itemPublicId, String warehousePublicId, String type,
                                      LocalDate from, LocalDate to, int page, int size);

    Map<String, Object> alerts();

    Map<String, Object> listCounts(String status, int page, int size);

    Map<String, Object> count(String publicId);

    Map<String, Object> createCount(Map<String, Object> body, AuthPrincipal actor, String ip);

    Map<String, Object> updateCountLine(String countPublicId, String linePublicId,
                                        Map<String, Object> body, AuthPrincipal actor);

    Map<String, Object> postCount(String countPublicId, Map<String, Object> body,
                                  AuthPrincipal actor, String ip);

    Map<String, Object> listPurchaseRequests(String status, int page, int size);

    Map<String, Object> createPurchaseRequest(Map<String, Object> body, AuthPrincipal actor,
                                              String ip);

    Map<String, Object> approvePurchaseRequest(String publicId, Map<String, Object> body,
                                               AuthPrincipal actor, String ip);

    Map<String, Object> listPurchaseOrders(String status, int page, int size);

    Map<String, Object> purchaseOrder(String publicId);

    Map<String, Object> createPurchaseOrder(Map<String, Object> body, AuthPrincipal actor,
                                            String ip);

    Map<String, Object> receivePurchaseOrder(String publicId, Map<String, Object> body,
                                             AuthPrincipal actor, String ip);

    Map<String, Object> valuation(String warehousePublicId);

    Map<String, Object> consumption(LocalDate from, LocalDate to, String itemPublicId);
}
