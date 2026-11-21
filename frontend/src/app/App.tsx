import { Navigate, Route, Routes } from "react-router-dom";
import { AppShell } from "../shared/layout/AppShell";
import { PermissionGate, ProtectedRoute } from "../shared/auth/ProtectedRoute";
import { LoginPage } from "../modules/auth/LoginPage";
import { AdminPage } from "../modules/admin/AdminPage";
import { Dashboard } from "../modules/dashboard/Dashboard";
import { Placeholder } from "../modules/placeholder/Placeholder";
import { PatientsPage } from "../modules/patients/PatientsPage";
import { PatientCardPage } from "../modules/patients/PatientCardPage";
import { AppointmentsPage } from "../modules/scheduling/AppointmentsPage";
import { EncountersPage } from "../modules/clinical/EncountersPage";
import { EncounterPage } from "../modules/clinical/EncounterPage";
import { ErBoardPage } from "../modules/er/ErBoardPage";
import { AdtPage } from "../modules/adt/AdtPage";
import { NursingPage } from "../modules/nursing/NursingPage";
import { LabPage } from "../modules/lab/LabPage";
import { PharmacyPage } from "../modules/pharmacy/PharmacyPage";
import { BillingPage } from "../modules/billing/BillingPage";
import { InventoryPage } from "../modules/inventory/InventoryPage";

const ADMIN_PERMISSIONS = [
  "IAM:READ",
  "IAM:WRITE",
  "IAM:BREAK_GLASS",
  "AUDIT:READ",
  "SYS:CONFIG",
];

const PATIENT_PERMISSIONS = ["PAT:READ"];
const SCHEDULING_PERMISSIONS = ["SCH:READ"];
const CLINICAL_PERMISSIONS = ["EMR:READ"];

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<Navigate to="/app/dashboard" replace />} />
      <Route element={<ProtectedRoute />}>
        <Route path="/app" element={<AppShell />}>
          <Route index element={<Navigate to="dashboard" replace />} />
          <Route path="dashboard" element={<Dashboard />} />
          <Route
            path="patients"
            element={
              <PermissionGate anyOf={PATIENT_PERMISSIONS}>
                <PatientsPage />
              </PermissionGate>
            }
          />
          <Route
            path="patients/:publicId"
            element={
              <PermissionGate anyOf={PATIENT_PERMISSIONS}>
                <PatientCardPage />
              </PermissionGate>
            }
          />
          <Route
            path="appointments"
            element={
              <PermissionGate anyOf={SCHEDULING_PERMISSIONS}>
                <AppointmentsPage />
              </PermissionGate>
            }
          />
          <Route
            path="clinical"
            element={
              <PermissionGate anyOf={CLINICAL_PERMISSIONS}>
                <EncountersPage />
              </PermissionGate>
            }
          />
          <Route
            path="clinical/:publicId"
            element={
              <PermissionGate anyOf={CLINICAL_PERMISSIONS}>
                <EncounterPage />
              </PermissionGate>
            }
          />
          <Route
            path="er"
            element={
              <PermissionGate anyOf={["ER:READ"]}>
                <ErBoardPage />
              </PermissionGate>
            }
          />
          <Route
            path="admission"
            element={
              <PermissionGate anyOf={["ADT:READ"]}>
                <AdtPage />
              </PermissionGate>
            }
          />
          <Route
            path="nursing"
            element={
              <PermissionGate anyOf={["NUR:READ"]}>
                <NursingPage />
              </PermissionGate>
            }
          />
          <Route
            path="laboratory"
            element={
              <PermissionGate anyOf={["LAB:READ"]}>
                <LabPage />
              </PermissionGate>
            }
          />
          <Route
            path="pharmacy"
            element={
              <PermissionGate anyOf={["PHR:READ"]}>
                <PharmacyPage />
              </PermissionGate>
            }
          />
          <Route
            path="billing"
            element={
              <PermissionGate anyOf={["BIL:READ"]}>
                <BillingPage />
              </PermissionGate>
            }
          />
          <Route
            path="inventory"
            element={
              <PermissionGate anyOf={["INV:READ"]}>
                <InventoryPage />
              </PermissionGate>
            }
          />
          <Route path="analytics" element={<Placeholder titleKey="nav.analytics" phase="7" />} />
          <Route
            path="admin"
            element={
              <PermissionGate anyOf={ADMIN_PERMISSIONS}>
                <AdminPage />
              </PermissionGate>
            }
          />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/app/dashboard" replace />} />
    </Routes>
  );
}
