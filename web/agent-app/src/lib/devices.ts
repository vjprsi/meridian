"use client";

import { api, idem, unwrap } from "@/api/client";
import { config } from "@/lib/config";
import { deviceModel, deviceSerial, deviceType } from "@/lib/device";
import { deviceKey, newDeviceKey } from "@/lib/device-key";

/**
 * Registers this installation with the agent channel (agentMeDevicesCreate): serial number and the public half of the
 * device key pair (the private key never leaves the device). `fresh` generates a new key pair first; the server then
 * revokes the previous key of the device. Needs a recent sign-in. Returns the device id.
 */
export async function registerDevice(outletId: string, fresh = false): Promise<string> {
  const key = fresh ? await newDeviceKey() : await deviceKey();
  const r = unwrap(await api.POST("/v1/agent/devices", {
    params: { header: idem() },
    body: {
      serial_number: deviceSerial(), device_type: deviceType(), agent_outlet_id: outletId, model: deviceModel(),
      app_version: config.appVersion, key_algorithm: key.algorithm, public_key: key.publicKey,
    },
  }));
  return r.agent_device_id;
}
