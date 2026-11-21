import { useQuery } from "@tanstack/react-query";
import { api } from "./client";

export type HealthStatus = "UP" | "DEGRADED" | "DOWN";

export interface HealthCheck {
  name: string;
  status: "UP" | "WARN" | "DOWN";
  detail: string;
  latencyMs: number;
}

export interface HealthResult {
  status: HealthStatus;
  version: string;
  environment: string;
  timestamp: string;
  uptimeMs: number;
  checks: HealthCheck[];
}

export function useHealth() {
  return useQuery({
    queryKey: ["health"],
    queryFn: () => api<HealthResult>("/health"),
    refetchInterval: 30_000,
    retry: 1,
    staleTime: 0,
  });
}
