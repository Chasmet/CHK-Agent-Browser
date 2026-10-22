import { createGateway } from "./gateway.ts";

// Preserve the existing function name, URL and Windows configuration.
Deno.serve(createGateway());
