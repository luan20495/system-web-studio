"use client";
import { createContext, useContext } from "react";

/** the workspace the Studio shell works in; provided by `StudioApp`, read by the screens */
export type StudioCtx = { workspaceId: string; setWorkspaceId: (id: string) => void; dedicated: boolean };
export const Ctx = createContext<StudioCtx | null>(null);
export const useStudio = () => useContext(Ctx)!;
