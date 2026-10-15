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

import "./process-shim";
import "@carbon/styles/css/styles.min.css";
import "./config.css";
import React, {useEffect, useMemo, useState} from "react";
import {createRoot} from "react-dom/client";
import {Button, InlineNotification, PasswordInput, Stack, TextInput, Tile} from "@carbon/react";
import {ValtimoPluginSDK} from "@valtimo/plugin-sdk/frontend";

const sdk = new ValtimoPluginSDK();

// Local dev stack: Open Plan on host port 7501, seen from the plugin host container.
const LOCAL_DEFAULTS = {
  title: "Open Plan",
  openPlanUrl: "http://host.docker.internal:7501",
  openPlanToken: "pdca-openplan-dev-token-0123456789abcdef",
};

function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

function ConfigForm() {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(LOCAL_DEFAULTS.title);
  const [openPlanUrl, setOpenPlanUrl] = useState(LOCAL_DEFAULTS.openPlanUrl);
  const [openPlanToken, setOpenPlanToken] = useState(LOCAL_DEFAULTS.openPlanToken);
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    // Edit mode only: secrets are never sent back, so an empty token keeps the stored one.
    sdk.onPrefillConfiguration(({title: prefillTitle, configuration}) => {
      setEditing(true);
      setTitle(prefillTitle ?? "");
      setOpenPlanUrl((configuration.openPlanUrl as string) ?? "");
      setOpenPlanToken("");
    });
    sdk.emit("ready", {});
  }, []);

  const urlValid = isHttpUrl(openPlanUrl.trim());
  const titleValid = title.trim().length > 0;
  const tokenValid = editing || openPlanToken.trim().length > 0;
  const valid = urlValid && titleValid && tokenValid;

  const data = useMemo(() => {
    const properties: Record<string, unknown> = {openPlanUrl: openPlanUrl.trim().replace(/\/+$/, "")};
    if (openPlanToken.trim()) properties.openPlanToken = openPlanToken.trim();
    return properties;
  }, [openPlanUrl, openPlanToken]);

  useEffect(() => {
    sdk.setConfiguration(valid, title.trim(), data);
  }, [valid, title, data]);

  const useLocalDefaults = () => {
    setOpenPlanUrl(LOCAL_DEFAULTS.openPlanUrl);
    setOpenPlanToken(LOCAL_DEFAULTS.openPlanToken);
    if (!title.trim()) setTitle(LOCAL_DEFAULTS.title);
  };

  return (
    <div className="openplan-config">
      <Stack gap={6}>
        <TextInput
          id="openplan-title"
          labelText={sdk.t("config.title.label")}
          placeholder={sdk.t("config.title.placeholder")}
          value={title}
          invalid={touched && !titleValid}
          invalidText={sdk.t("config.title.required")}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={() => setTouched(true)}
        />

        <Tile className="openplan-config__connection">
          <Stack gap={6}>
            <div>
              <h4 className="openplan-config__heading">{sdk.t("config.connection.heading")}</h4>
              <p className="openplan-config__intro">{sdk.t("config.connection.intro")}</p>
            </div>

            <TextInput
              id="openplan-url"
              type="url"
              labelText={sdk.t("config.openPlanUrl.label")}
              placeholder="https://openplan.example.nl"
              helperText={sdk.t("config.openPlanUrl.help")}
              value={openPlanUrl}
              invalid={!urlValid}
              invalidText={sdk.t("config.openPlanUrl.invalid")}
              onChange={(e) => setOpenPlanUrl(e.target.value)}
            />

            <PasswordInput
              id="openplan-token"
              labelText={sdk.t("config.openPlanToken.label")}
              helperText={
                editing ? sdk.t("config.openPlanToken.keep") : sdk.t("config.openPlanToken.help")
              }
              placeholder={editing ? "••••••••" : ""}
              value={openPlanToken}
              invalid={touched && !tokenValid}
              invalidText={sdk.t("config.openPlanToken.required")}
              onChange={(e) => setOpenPlanToken(e.target.value)}
              onBlur={() => setTouched(true)}
              showPasswordLabel={sdk.t("config.openPlanToken.show")}
              hidePasswordLabel={sdk.t("config.openPlanToken.hide")}
            />

            <div>
              <Button kind="tertiary" size="sm" onClick={useLocalDefaults}>
                {sdk.t("config.localDefaults.button")}
              </Button>
            </div>
          </Stack>
        </Tile>

        <InlineNotification
          kind="info"
          lowContrast
          hideCloseButton
          title={sdk.t("config.egress.title")}
          subtitle={sdk.t("config.egress.subtitle")}
        />
      </Stack>
    </div>
  );
}

sdk.ready().then(() => {
  createRoot(document.getElementById("root")!).render(<ConfigForm />);
});
