"use client";
/**
 * Which console is rendering (instead of module variables assigned during render): the console, the helper that builds its in-app paths, and "does this console own that section".
 * Without a provider the default is the legacy combined console ("all", /admin), which is what screens mounted on their own (test harnesses) always assumed.
 */
import { createContext, useContext } from "react";
import { adminBase, type AdminPortal } from "../base";

export type AdminConsole = {
  portal: AdminPortal;
  /** absolute in-app path of an admin screen of THIS console: A("/users/1") -> "/admin/users/1" (or "/platform/...") */
  A: (path?: string) => string;
  owns: (key: string) => boolean;
};

export const makeAdminConsole = (portal: AdminPortal, owns: (key: string) => boolean): AdminConsole => ({ portal, A: (path = "") => adminBase(portal) + path, owns });
const DEFAULT_CONSOLE: AdminConsole = makeAdminConsole("all", () => true);

export const AdminConsoleContext = createContext<AdminConsole>(DEFAULT_CONSOLE);
export const useAdminConsole = (): AdminConsole => useContext(AdminConsoleContext);
export const useA = (): AdminConsole["A"] => useContext(AdminConsoleContext).A;
