"use client";

import { PortalApp } from "@xweb/auth";
import { StudioApp } from "@/features/studio/StudioApp";

export default function Entry() {
  return <PortalApp portal="studio" render={(seg) => <StudioApp seg={seg} dedicated/>}/>;
}
