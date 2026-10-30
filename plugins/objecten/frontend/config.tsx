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

// Local GZAC stack (gzac-docker-compose): Objecten API on host port 8010 with its fixture
// token, seen from the plugin host container.
const LOCAL_DEFAULTS = {
  title: "Objecten API",
  objectenUrl: "http://host.docker.internal:8010",
  objectenToken: "182c13e2209161852c53cef53a879f7a2f923430",
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
  const [objectenUrl, setObjectenUrl] = useState(LOCAL_DEFAULTS.objectenUrl);
  const [objectenToken, setObjectenToken] = useState(LOCAL_DEFAULTS.objectenToken);
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    // Edit mode only: secrets are never sent back, so an empty token keeps the stored one.
    sdk.onPrefillConfiguration(({title: prefillTitle, configuration}) => {
      setEditing(true);
      setTitle(prefillTitle ?? "");
      setObjectenUrl((configuration.objectenUrl as string) ?? "");
      setObjectenToken("");
    });
    sdk.emit("ready", {});
  }, []);

  const urlValid = isHttpUrl(objectenUrl.trim());
  const titleValid = title.trim().length > 0;
  const tokenValid = editing || objectenToken.trim().length > 0;
  const valid = urlValid && titleValid && tokenValid;

  const data = useMemo(() => {
    const properties: Record<string, unknown> = {objectenUrl: objectenUrl.trim().replace(/\/+$/, "")};
    if (objectenToken.trim()) properties.objectenToken = objectenToken.trim();
    return properties;
  }, [objectenUrl, objectenToken]);

  useEffect(() => {
    sdk.setConfiguration(valid, title.trim(), data);
  }, [valid, title, data]);

  const useLocalDefaults = () => {
    setObjectenUrl(LOCAL_DEFAULTS.objectenUrl);
    setObjectenToken(LOCAL_DEFAULTS.objectenToken);
    if (!title.trim()) setTitle(LOCAL_DEFAULTS.title);
  };

  return (
    <div className="objecten-config">
      <Stack gap={6}>
        <TextInput
          id="objecten-title"
          labelText={sdk.t("config.title.label")}
          placeholder={sdk.t("config.title.placeholder")}
          value={title}
          invalid={touched && !titleValid}
          invalidText={sdk.t("config.title.required")}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={() => setTouched(true)}
        />

        <Tile className="objecten-config__connection">
          <Layer>
            <Stack gap={6}>
              <div>
                <h4 className="objecten-config__heading">{sdk.t("config.connection.heading")}</h4>
                <p className="objecten-config__intro">{sdk.t("config.connection.intro")}</p>
              </div>

              <TextInput
                id="objecten-url"
                type="url"
                labelText={sdk.t("config.objectenUrl.label")}
                placeholder="https://objecten.example.nl"
                helperText={sdk.t("config.objectenUrl.help")}
                value={objectenUrl}
                invalid={!urlValid}
                invalidText={sdk.t("config.objectenUrl.invalid")}
                onChange={(e) => setObjectenUrl(e.target.value)}
              />

              <PasswordInput
                id="objecten-token"
                labelText={sdk.t("config.objectenToken.label")}
                helperText={
                  editing ? sdk.t("config.objectenToken.keep") : sdk.t("config.objectenToken.help")
                }
                placeholder={editing ? "••••••••" : ""}
                value={objectenToken}
                invalid={touched && !tokenValid}
                invalidText={sdk.t("config.objectenToken.required")}
                onChange={(e) => setObjectenToken(e.target.value)}
                onBlur={() => setTouched(true)}
                showPasswordLabel={sdk.t("config.objectenToken.show")}
                hidePasswordLabel={sdk.t("config.objectenToken.hide")}
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
