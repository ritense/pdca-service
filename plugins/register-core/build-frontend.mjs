/*
 * Copyright 2015-2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Builds a plugin's configuration screen (ui/config.tsx) into the production
// bundles frontend/config.bundle.{js,css}, minified and with React's
// production build. Run from the plugin directory before valtimo-plugin-pack:
// the pack step only compiles frontend/<name>.tsx sources, so with the source
// in ui/ it packs these bundles as they are.

import {build} from "esbuild";

await build({
  absWorkingDir: process.cwd(),
  entryPoints: {"config.bundle": "ui/config.tsx"},
  outdir: "frontend",
  bundle: true,
  format: "iife",
  target: "es2020",
  jsx: "automatic",
  minify: true,
  define: {"process.env.NODE_ENV": "\"production\""},
  logLevel: "warning",
});
