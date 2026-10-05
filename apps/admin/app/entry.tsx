"use client";

import { PortalApp } from "@xweb/auth";
import { AdminApp } from "@/features/admin/AdminApp";

export default function Entry() {
  return <PortalApp portal="admin" render={(seg) => <AdminApp seg={seg} portal="admin"/>}/>;
}
