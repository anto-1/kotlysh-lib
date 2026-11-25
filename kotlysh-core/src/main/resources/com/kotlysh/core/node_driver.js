'use strict';

const readline = require('readline');
const vm = require('vm');
const isProxy = require('util').types.isProxy;
const isPromise = require('util').types.isPromise;
const NativePromise = Promise;
const promiseThen = Promise.prototype.then;
// Give scriptlets their own language globals. Sharing the driver's globalThis
// lets ordinary assignments to JSON, Number, or Buffer corrupt the protocol.
const hostGlobal = globalThis;
const sandbox = Object.create(null);
const context = vm.createContext(sandbox);
const languageGlobals = new Set(vm.runInContext('Object.getOwnPropertyNames(globalThis)', context));
for (const name of Object.getOwnPropertyNames(hostGlobal)) {
  if (name === 'global' || name === 'globalThis') continue;
  // The default VM console is silent; expose Node's real console explicitly.
  if (languageGlobals.has(name) && name !== 'console') continue;
  const property = Object.getOwnPropertyDescriptor(hostGlobal, name);
  if ('value' in property) {
    Object.defineProperty(sandbox, name, {
      value: property.value, writable: true, configurable: true, enumerable: property.enumerable,
    });
  } else {
    // Preserve lazy Node APIs without copying setters that mutate host globals.
    Object.defineProperty(sandbox, name, {
      get: () => Reflect.get(hostGlobal, name),
      set: value => Object.defineProperty(sandbox, name, {
        value, writable: true, configurable: true, enumerable: property.enumerable,
      }),
      configurable: true, enumerable: property.enumerable,
    });
  }
}
vm.runInContext('globalThis.global = globalThis', context);
const objectPrototype = Object.prototype;
const arrayPrototype = Array.prototype;
// VM literals keep their intrinsic prototype even when global constructors are shared.
const contextObjectPrototype = vm.runInContext('Object.getPrototypeOf({})', context);
const contextArrayPrototype = vm.runInContext('Object.getPrototypeOf([])', context);
const createScriptJsonObject = vm.runInContext('() => ({})', context);
const createScriptJsonArray = vm.runInContext('() => []', context);
const objects = new Map();
let nextReference = 0;
const writeOut = process.stdout.write.bind(process.stdout);
const writeErr = process.stderr.write.bind(process.stderr);

// Build JSON data explicitly so getters, toJSON, undefined and unsafe numbers
// cannot silently change the value at a language boundary.
function jsonValue(value, active = new Set()) {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new TypeError('JSON cannot represent non-finite numbers');
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) {
      throw new TypeError('Use a string or retained BigInt for integers outside the JavaScript safe range');
    }
    return value;
  }
  if (typeof value !== 'object') throw new TypeError('Convert ' + typeof value + ' to JSON data or keep it in a reference');
  if (isProxy(value)) throw new TypeError('Convert proxies to plain JSON data or keep them in a reference');
  if (active.has(value)) throw new TypeError('JSON cannot represent cyclic objects');
  active.add(value);
  try {
    if (Array.isArray(value)) {
      const prototype = Object.getPrototypeOf(value);
      if (prototype !== null && prototype !== arrayPrototype && prototype !== contextArrayPrototype) {
        throw new TypeError('Convert this array to plain JSON data or keep it in a reference');
      }
      for (const key of Reflect.ownKeys(value)) {
        const property = Object.getOwnPropertyDescriptor(value, key);
        if (!property.enumerable) continue;
        if (typeof key !== 'string' || !/^(0|[1-9][0-9]*)$/.test(key) || Number(key) >= value.length) {
          throw new TypeError('JSON arrays cannot represent extra enumerable properties; convert explicitly');
        }
      }
      return Array.from({length: value.length}, (_, index) => {
        const property = Object.getOwnPropertyDescriptor(value, String(index));
        if (!property || !('value' in property)) throw new TypeError('JSON arrays must have explicit values');
        return jsonValue(property.value, active);
      });
    }
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== null && prototype !== objectPrototype && prototype !== contextObjectPrototype) {
      throw new TypeError('Convert this object to plain JSON data or keep it in a reference');
    }
    const result = Object.create(null);
    for (const key of Reflect.ownKeys(value)) {
      const property = Object.getOwnPropertyDescriptor(value, key);
      if (!property.enumerable) continue;
      if (typeof key !== 'string' || !('value' in property)) {
        throw new TypeError('JSON objects require string keys and data properties');
      }
      result[key] = jsonValue(property.value, active);
    }
    return result;
  } finally {
    active.delete(value);
  }
}

// JSON imports belong to the script's realm, including nested objects/arrays.
// Normalize in the driver first so inherited user hooks stay out of transport.
// Realm literal factories avoid mutable globals/constructors. Define own data
// properties to bypass prototype setters and preserve __proto__ as an ordinary key.
// Copy primitives directly: a stringify/parse round trip would erase negative zero.
function scriptJsonValue(value) {
  function materialize(value) {
    if (value === null || typeof value !== 'object') return value;
    const result = Array.isArray(value) ? createScriptJsonArray() : createScriptJsonObject();
    for (const key of Object.keys(value)) {
      Object.defineProperty(result, key, {
        value: materialize(value[key]), writable: true, configurable: true, enumerable: true,
      });
    }
    return result;
  }
  return materialize(jsonValue(value));
}

function resolve(binding) {
  const keys = Object.keys(binding);
  if (keys.length === 1 && keys[0] === 'json') return scriptJsonValue(binding.json);
  if (keys.length === 1 && keys[0] === 'ref') {
    if (!objects.has(binding.ref)) throw new ReferenceError('Released or unknown reference');
    return objects.get(binding.ref);
  }
  throw new TypeError('A binding must contain exactly one json value or reference');
}

function retain(value) {
  const id = String(++nextReference);
  objects.set(id, value);
  return {kind: 'ref', id};
}

// Keep plain data/handles out of Promise thenable assimilation. Only genuine
// promises are awaited; caller-written await or Promise.resolve remains explicit.
function settlePromiseResult(value) {
  return new NativePromise((resolve, reject) => {
    if (isPromise(value)) {
      promiseThen.call(value, result => resolve({then: void 0, value: result}), reject);
    } else {
      resolve({then: void 0, value});
    }
  });
}

async function dispatch(message) {
  if (message.version !== 1) throw new Error('Unsupported Kotlysh protocol version');
  switch (message.op) {
    case 'exec': {
      await settlePromiseResult(vm.runInContext(Buffer.from(message.code, 'base64').toString('utf8'), context));
      return {kind: 'unit'};
    }
    case 'eval':
    case 'hold': {
      const bindings = message.bindings || {};
      const names = Object.keys(bindings);
      if (names.some(name => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(name))) throw new TypeError('Invalid binding name');
      const values = names.map(name => resolve(bindings[name]));
      const code = Buffer.from(message.code, 'base64').toString('utf8');
      const evaluate = vm.runInContext('(async (' + names.join(',') + ') => ({then: void 0, value: (\n' + code + '\n)}))', context);
      // This fresh promise belongs to the driver. Native then also reads its
      // constructor/species, so keep user prototype hooks out of bookkeeping.
      // Returned user promises retain their own properties and semantics.
      const evaluation = evaluate(...values);
      Object.defineProperty(evaluation, 'constructor', {value: NativePromise});
      const evaluated = (await settlePromiseResult(evaluation)).value;
      const result = (await settlePromiseResult(evaluated.value)).value;
      return message.op === 'hold' ? retain(result) : {kind: 'value', value: jsonValue(result)};
    }
    case 'store': return retain(scriptJsonValue(message.value));
    case 'get': {
      if (!objects.has(message.id)) throw new ReferenceError('Released or unknown reference');
      return {kind: 'value', value: jsonValue(objects.get(message.id))};
    }
    case 'release':
      objects.delete(message.id);
      return {kind: 'unit'};
    default: throw new Error('Unknown operation: ' + message.op);
  }
}

function errorTextProperty(error, name) {
  try {
    const value = error?.[name];
    return typeof value === 'string' ? value : undefined;
  } catch (_) {
    return undefined;
  }
}

function printableError(error) {
  try {
    return String(error);
  } catch (_) {
    return '<unprintable thrown value>';
  }
}

async function main() {
  const input = readline.createInterface({input: process.stdin, terminal: false});
  for await (const line of input) {
    const message = JSON.parse(line);
    let status = 0;
    let response;
    try {
      response = await dispatch(message);
    } catch (error) {
      status = 1;
      const type = errorTextProperty(error, 'name') || 'Error';
      const message = errorTextProperty(error, 'message') ?? printableError(error);
      const detail = errorTextProperty(error, 'stack') || type + ': ' + message;
      writeErr(detail + '\n');
      response = {kind: 'error', type, message};
    }
    response.version = 1;
    const payload = Buffer.from(JSON.stringify(response), 'utf8').toString('base64');
    const marker = message.marker + ':' + status;
    writeOut(marker + ':' + payload + '\n');
    writeErr(marker + '\n');
  }
}

main().catch(error => { writeErr(String(error) + '\n'); process.exitCode = 1; });
