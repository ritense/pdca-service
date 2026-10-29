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
import {
  Button,
  InlineNotification,
  Layer,
  PasswordInput,
  Stack,
  TextInput,
  Tile,
} from "@carbon/react";
import {ValtimoPluginSDK} from "@valtimo/plugin-sdk/frontend";

const sdk = new ValtimoPluginSDK();

// Local dev stack: Open Product on host port 7502, seen from the plugin host container.
const LOCAL_DEFAULTS = {
  title: "Open Product",
  openProductUrl: "http://host.docker.internal:7502",
  openProductToken: "pdca-openproduct-dev-token-0123456789abc",
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
  const [openProductUrl, setOpenProductUrl] = useState(LOCAL_DEFAULTS.openProductUrl);
  const [openProductToken, setOpenProductToken] = useState(LOCAL_DEFAULTS.openProductToken);
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    // Edit mode only: secrets are never sent back, so an empty token keeps the stored one.
    sdk.onPrefillConfiguration(({title: prefillTitle, configuration}) => {
      setEditing(true);
      setTitle(prefillTitle ?? "");
      setOpenProductUrl((configuration.openProductUrl as string) ?? "");
      setOpenProductToken("");
    });
    sdk.emit("ready", {});
  }, []);

  const urlValid = isHttpUrl(openProductUrl.trim());
  const titleValid = title.trim().length > 0;
  const tokenValid = editing || openProductToken.trim().length > 0;
  const valid = urlValid && titleValid && tokenValid;

  const data = useMemo(() => {
    const properties: Record<string, unknown> = {openProductUrl: openProductUrl.trim().replace(/\/+$/, "")};
    if (openProductToken.trim()) properties.openProductToken = openProductToken.trim();
    return properties;
  }, [openProductUrl, openProductToken]);

  useEffect(() => {
    sdk.setConfiguration(valid, title.trim(), data);
  }, [valid, title, data]);

  const useLocalDefaults = () => {
    setOpenProductUrl(LOCAL_DEFAULTS.openProductUrl);
    setOpenProductToken(LOCAL_DEFAULTS.openProductToken);
    if (!title.trim()) setTitle(LOCAL_DEFAULTS.title);
  };

  return (
    <div className="openproduct-config">
      <Stack gap={6}>
        <TextInput
          id="openproduct-title"
          labelText={sdk.t("config.title.label")}
          placeholder={sdk.t("config.title.placeholder")}
          value={title}
          invalid={touched && !titleValid}
          invalidText={sdk.t("config.title.required")}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={() => setTouched(true)}
        />

        <Tile className="openproduct-config__connection">
          <Layer>
            <Stack gap={6}>
              <div>
                <h4 className="openproduct-config__heading">{sdk.t("config.connection.heading")}</h4>
                <p className="openproduct-config__intro">{sdk.t("config.connection.intro")}</p>
              </div>

              <TextInput
                id="openproduct-url"
                type="url"
                labelText={sdk.t("config.openProductUrl.label")}
                placeholder="https://openproduct.example.nl"
                helperText={sdk.t("config.openProductUrl.help")}
                value={openProductUrl}
                invalid={!urlValid}
                invalidText={sdk.t("config.openProductUrl.invalid")}
                onChange={(e) => setOpenProductUrl(e.target.value)}
              />

              <PasswordInput
                id="openproduct-token"
                labelText={sdk.t("config.openProductToken.label")}
                helperText={
                  editing ? sdk.t("config.openProductToken.keep") : sdk.t("config.openProductToken.help")
                }
                placeholder={editing ? "••••••••" : ""}
                value={openProductToken}
                invalid={touched && !tokenValid}
                invalidText={sdk.t("config.openProductToken.required")}
                onChange={(e) => setOpenProductToken(e.target.value)}
                onBlur={() => setTouched(true)}
                showPasswordLabel={sdk.t("config.openProductToken.show")}
                hidePasswordLabel={sdk.t("config.openProductToken.hide")}
              />

              <div>
                <Button kind="tertiary" size="sm" onClick={useLocalDefaults}>
                  {sdk.t("config.localDefaults.button")}
                </Button>
              </div>
            </Stack>
          </Layer>
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
