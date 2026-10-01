import StudioShell from "@/components/StudioShell";
import HttpStudio from "@/components/HttpStudio";
import { isDemoMode } from "@/lib/api-client";

export default function Page() {
  return isDemoMode ? <StudioShell /> : <HttpStudio />;
}
