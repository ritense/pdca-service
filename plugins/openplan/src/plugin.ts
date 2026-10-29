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

import {httpRequest, log, onContractCall} from "@valtimo/plugin-sdk";
import {handleOperation, OPENPLAN_CONTRACT, OPERATIONS, type HttpCall, type HttpReply} from "./openplan";

const transport = (call: HttpCall): HttpReply => httpRequest.request(call);

for (const operation of OPERATIONS) {
  onContractCall(OPENPLAN_CONTRACT, operation, (input) => {
    const output = handleOperation(operation, input.payload, input.configuration, transport);
    if (output.status === "error") {
      log.warn(`Open Plan ${operation} failed: [${output.errorCode}] ${output.errorMessage}`, {
        caller: input.caller,
      });
    }
    return output;
  });
}
