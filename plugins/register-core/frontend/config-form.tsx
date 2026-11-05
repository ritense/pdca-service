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
import {useEffect, useMemo, useState} from "react";
import {createRoot} from "react-dom/client";
import {Button, InlineNotification, Layer, PasswordInput, Stack, TextInput, Tile} from "@carbon/react";
import {ValtimoPluginSDK} from "@valtimo/plugin-sdk/frontend";
import {configurationData, fieldValid, type ConfigField, type ConfigFormSpec} from "./config-fields";

export type {ConfigField, ConfigFormSpec};

function localDefaults(fields: ConfigField[]): Record<string, string> {
  return Object.fromEntries(fields.map((field) => [field.key, field.localDefault]));
}

function ConfigForm({sdk, spec}: {sdk: ValtimoPluginSDK; spec: ConfigFormSpec}) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(spec.defaultTitle);
  const [values, setValues] = useState<Record<string, string>>(() => localDefaults(spec.fields));
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    // Edit mode only: secrets are never sent back, so an empty secret keeps the stored one.
    sdk.onPrefillConfiguration(({title: prefillTitle, configuration}) => {
      setEditing(true);
      setTitle(prefillTitle ?? "");
      setValues(
        Object.fromEntries(
          spec.fields.map((field) => [field.key, field.kind === "secret" ? "" : ((configuration[field.key] as string) ?? "")])
        )
      );
    });
    sdk.emit("ready", {});
  }, [sdk, spec]);

  const titleValid = title.trim().length > 0;
  const valid = titleValid && spec.fields.every((field) => fieldValid(field, values[field.key] ?? "", editing));
  const data = useMemo(() => configurationData(spec.fields, values), [spec, values]);

  useEffect(() => {
    sdk.setConfiguration(valid, title.trim(), data);
  }, [sdk, valid, title, data]);

  const setValue = (key: string, value: string) => setValues((current) => ({...current, [key]: value}));
  const useLocalDefaults = () => {
    setValues(localDefaults(spec.fields));
    if (!title.trim()) setTitle(spec.defaultTitle);
  };

  return (
    <div className="register-config">
      <Stack gap={6}>
        <TextInput
          id="register-config-title"
          labelText={sdk.t("config.title.label")}
          placeholder={sdk.t("config.title.placeholder")}
          value={title}
          invalid={touched && !titleValid}
          invalidText={sdk.t("config.title.required")}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={() => setTouched(true)}
        />

        <Tile className="register-config__connection">
          <Layer>
            <Stack gap={6}>
              <div>
                <h4 className="register-config__heading">{sdk.t("config.connection.heading")}</h4>
                <p className="register-config__intro">{sdk.t("config.connection.intro")}</p>
              </div>

              {spec.fields.map((field) => {
                const value = values[field.key] ?? "";
                const fieldIsValid = fieldValid(field, value, editing);
                return field.kind === "secret" ? (
                  <PasswordInput
                    key={field.key}
                    id={`register-config-${field.key}`}
                    labelText={sdk.t(`config.${field.key}.label`)}
                    helperText={sdk.t(editing ? `config.${field.key}.keep` : `config.${field.key}.help`)}
                    placeholder={editing ? "••••••••" : ""}
                    value={value}
                    invalid={touched && !fieldIsValid}
                    invalidText={sdk.t(`config.${field.key}.required`)}
                    onChange={(e) => setValue(field.key, e.target.value)}
                    onBlur={() => setTouched(true)}
                    showPasswordLabel={sdk.t(`config.${field.key}.show`)}
                    hidePasswordLabel={sdk.t(`config.${field.key}.hide`)}
                  />
                ) : (
                  <TextInput
                    key={field.key}
                    id={`register-config-${field.key}`}
                    type="url"
                    labelText={sdk.t(`config.${field.key}.label`)}
                    placeholder={field.placeholder}
                    helperText={sdk.t(`config.${field.key}.help`)}
                    value={value}
                    invalid={!fieldIsValid}
                    invalidText={sdk.t(`config.${field.key}.invalid`)}
                    onChange={(e) => setValue(field.key, e.target.value)}
                  />
                );
              })}

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

/** Renders a register plugin's configuration screen into `#root` once the SDK is ready. */
export function renderConfigForm(spec: ConfigFormSpec): void {
  const sdk = new ValtimoPluginSDK();
  sdk.ready().then(() => {
    createRoot(document.getElementById("root")!).render(<ConfigForm sdk={sdk} spec={spec} />);
  });
}
