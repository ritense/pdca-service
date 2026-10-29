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

// Local dev stack: Open Klant on host port 7504, seen from the plugin host container.
const LOCAL_DEFAULTS = {
  title: "Open Klant",
  openKlantUrl: "http://host.docker.internal:7504",
  openKlantToken: "pdca-openklant-dev-token-0123456789abcde",
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
  const [openKlantUrl, setOpenKlantUrl] = useState(LOCAL_DEFAULTS.openKlantUrl);
  const [openKlantToken, setOpenKlantToken] = useState(LOCAL_DEFAULTS.openKlantToken);
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    // Edit mode only: secrets are never sent back, so an empty token keeps the stored one.
    sdk.onPrefillConfiguration(({title: prefillTitle, configuration}) => {
      setEditing(true);
      setTitle(prefillTitle ?? "");
      setOpenKlantUrl((configuration.openKlantUrl as string) ?? "");
      setOpenKlantToken("");
    });
    sdk.emit("ready", {});
  }, []);

  const urlValid = isHttpUrl(openKlantUrl.trim());
  const titleValid = title.trim().length > 0;
  const tokenValid = editing || openKlantToken.trim().length > 0;
  const valid = urlValid && titleValid && tokenValid;

  const data = useMemo(() => {
    const properties: Record<string, unknown> = {openKlantUrl: openKlantUrl.trim().replace(/\/+$/, "")};
    if (openKlantToken.trim()) properties.openKlantToken = openKlantToken.trim();
    return properties;
  }, [openKlantUrl, openKlantToken]);

  useEffect(() => {
    sdk.setConfiguration(valid, title.trim(), data);
  }, [valid, title, data]);

  const useLocalDefaults = () => {
    setOpenKlantUrl(LOCAL_DEFAULTS.openKlantUrl);
    setOpenKlantToken(LOCAL_DEFAULTS.openKlantToken);
    if (!title.trim()) setTitle(LOCAL_DEFAULTS.title);
  };

  return (
    <div className="openklant-config">
      <Stack gap={6}>
        <TextInput
          id="openklant-title"
          labelText={sdk.t("config.title.label")}
          placeholder={sdk.t("config.title.placeholder")}
          value={title}
          invalid={touched && !titleValid}
          invalidText={sdk.t("config.title.required")}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={() => setTouched(true)}
        />

        <Tile className="openklant-config__connection">
          <Layer>
            <Stack gap={6}>
              <div>
                <h4 className="openklant-config__heading">{sdk.t("config.connection.heading")}</h4>
                <p className="openklant-config__intro">{sdk.t("config.connection.intro")}</p>
              </div>

              <TextInput
                id="openklant-url"
                type="url"
                labelText={sdk.t("config.openKlantUrl.label")}
                placeholder="https://openklant.example.nl"
                helperText={sdk.t("config.openKlantUrl.help")}
                value={openKlantUrl}
                invalid={!urlValid}
                invalidText={sdk.t("config.openKlantUrl.invalid")}
                onChange={(e) => setOpenKlantUrl(e.target.value)}
              />

              <PasswordInput
                id="openklant-token"
                labelText={sdk.t("config.openKlantToken.label")}
                helperText={
                  editing ? sdk.t("config.openKlantToken.keep") : sdk.t("config.openKlantToken.help")
                }
                placeholder={editing ? "••••••••" : ""}
                value={openKlantToken}
                invalid={touched && !tokenValid}
                invalidText={sdk.t("config.openKlantToken.required")}
                onChange={(e) => setOpenKlantToken(e.target.value)}
                onBlur={() => setTouched(true)}
                showPasswordLabel={sdk.t("config.openKlantToken.show")}
                hidePasswordLabel={sdk.t("config.openKlantToken.hide")}
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
