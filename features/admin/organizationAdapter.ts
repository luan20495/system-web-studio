/**
 * The production binding of the organization screens: the typed service (organization.ts) over the typed transport `api.org` (packages/api-client/src/org.ts). This file names no URL;
 * every organization route is built in the api-client. The server flag ORGANIZATION_PERSISTENCE_ENABLED decides whether the routes answer or fail closed with 501 ORG_PERSISTENCE_NOT_AVAILABLE.
 */
import { api } from "@/lib/http-api";
import { createOrganizationApi } from "./organization";

export const liveOrganization = createOrganizationApi(api.org);
