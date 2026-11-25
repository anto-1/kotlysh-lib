"""Trusted scriptlets and structured requests share one persistent namespace."""
import base64
import contextlib
import io
import json
import math
import re
import sys
import traceback

# Keep transport entry points separate from user module/function replacements.
# JSON uses owned codec instances: retaining json.dumps alone would still look
# up a replaced JSONEncoder constructor. Shared classes/modules remain trusted.
request_decoder = json.JSONDecoder()
JSON_DECODE = request_decoder.decode
JSON_RAW_DECODE = request_decoder.raw_decode
JSON_ENCODE = json.JSONEncoder(allow_nan=False, ensure_ascii=True).encode
BASE64_DECODE = base64.b64decode
BASE64_ENCODE = base64.b64encode
COMPILE = compile
EXECUTE = exec
EVALUATE = eval


def utf8_stream(stream):
    """The JVM pipes use UTF-8 regardless of locale or PYTHONIOENCODING."""
    if hasattr(stream, "reconfigure"):
        # Supplying encoding alone resets errors to strict. Preserve each
        # stream's existing error policy and its identity, buffering and aliases.
        stream.reconfigure(encoding="utf-8", errors=stream.errors)
        return stream
    # Older Python 3 versions lack reconfigure. Keep their binary pipe and
    # line-buffering policy; write-through preserves the unbuffered driver.
    errors, line_buffering = stream.errors, stream.line_buffering
    return io.TextIOWrapper(stream.detach(), encoding="utf-8", errors=errors,
                            line_buffering=line_buffering, write_through=True)


for stream_name in ("stdin", "stdout", "stderr"):
    original_stream = getattr(sys, stream_name)
    configured_stream = utf8_stream(original_stream)
    setattr(sys, stream_name, configured_stream)
    if getattr(sys, "__" + stream_name + "__") is original_stream:
        setattr(sys, "__" + stream_name + "__", configured_stream)

namespace = {"__name__": "__main__"}
objects = {}
next_reference = 0
stdout, stderr = sys.stdout, sys.stderr


def json_value(value, active=None):
    """Reject lossy implicit conversions; arbitrary objects can stay in handles."""
    if value is None or type(value) in (str, bool, int):
        return value
    if type(value) is float:
        if not math.isfinite(value):
            raise ValueError("JSON cannot represent non-finite numbers")
        return value
    if type(value) not in (dict, list, tuple):
        raise TypeError("Convert " + type(value).__name__ + " to JSON data or keep it in a reference")
    active = set() if active is None else active
    identity = id(value)
    if identity in active:
        raise ValueError("JSON cannot represent cyclic objects")
    active.add(identity)
    try:
        if type(value) is dict:
            if any(type(key) is not str for key in value):
                raise TypeError("JSON object keys must be strings")
            return {key: json_value(item, active) for key, item in value.items()}
        return [json_value(item, active) for item in value]
    finally:
        active.remove(identity)


def resolve(binding):
    if set(binding) == {"json"}:
        return json_value(binding["json"])
    if set(binding) == {"ref"}:
        return objects[binding["ref"]]
    raise ValueError("A binding must contain exactly one json value or reference")


@contextlib.contextmanager
def bind(arguments):
    if any(not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name) for name in arguments):
        raise ValueError("Invalid binding name")
    resolved = {name: resolve(argument) for name, argument in arguments.items()}
    absent = object()
    previous = {name: namespace.get(name, absent) for name in resolved}
    namespace.update(resolved)
    try:
        yield
    finally:
        for name, value in previous.items():
            if value is absent:
                namespace.pop(name, None)
            else:
                namespace[name] = value


def retain(value):
    global next_reference
    next_reference += 1
    key = str(next_reference)
    objects[key] = value
    return {"kind": "ref", "id": key}


def dispatch(message):
    if message.get("version") != 1:
        raise ValueError("Unsupported Kotlysh protocol version")
    operation = message["op"]
    if operation == "exec":
        source = BASE64_DECODE(message["code"], validate=True).decode("utf-8")
        EXECUTE(COMPILE(source, "<kotlysh>", "exec"), namespace, namespace)
        return {"kind": "unit"}
    if operation in ("eval", "hold"):
        with bind(message.get("bindings", {})):
            source = BASE64_DECODE(message["code"], validate=True).decode("utf-8")
            result = EVALUATE(COMPILE(source, "<kotlysh-expression>", "eval"), namespace, namespace)
            if operation == "hold":
                return retain(result)
            return {"kind": "value", "value": json_value(result)}
    if operation == "store":
        return retain(json_value(message["value"]))
    if operation == "get":
        return {"kind": "value", "value": json_value(objects[message["id"]])}
    if operation == "release":
        objects.pop(message["id"], None)
        return {"kind": "unit"}
    raise ValueError("Unknown operation: " + operation)


def exception_type(error):
    try:
        name = type(error).__name__
        return name if type(name) is str else "Error"
    except BaseException:
        return "Error"


def exception_message(error):
    try:
        return str(error)
    except BaseException:
        return "<exception str() failed>"


def recover_request_marker(line):
    """Recover only a top-level string marker after decoding fails; never execute data."""
    depth, cursor, marker = 0, 0, None
    while cursor < len(line):
        character = line[cursor]
        if character == '"':
            text, end = JSON_RAW_DECODE(line, cursor)
            after = end
            while after < len(line) and line[after] in " \t\r\n":
                after += 1
            if depth == 1 and text == "marker" and after < len(line) and line[after] == ":":
                after += 1
                while after < len(line) and line[after] in " \t\r\n":
                    after += 1
                if after == len(line) or line[after] != '"':
                    raise ValueError("Request marker must be a string")
                value, _ = JSON_RAW_DECODE(line, after)
                if type(value) is not str or marker is not None:
                    raise ValueError("Invalid or duplicate request marker")
                marker = value
            cursor = end
            continue
        if character in "[{":
            depth += 1
        elif character in "]}":
            depth -= 1
        cursor += 1
    if marker is None:
        raise ValueError("Cannot recover request marker")
    return {"marker": marker}


for line in sys.stdin:
    message = None
    status = 0
    try:
        message = JSON_DECODE(line)
        response = dispatch(message)
        response["version"] = 1
        encoded = JSON_ENCODE(response)
    except BaseException as error:
        if message is None:
            # Decoder recursion and numeric conversion limits can fail before
            # constructing the envelope. Scan without recursing into its data.
            message = recover_request_marker(line)
        status = 1
        error_type, error_message = exception_type(error), exception_message(error)
        try:
            traceback.print_exc(file=stderr)
        except BaseException:
            stderr.write(error_type + ": " + error_message + "\n")
        response = {"version": 1, "kind": "error", "type": error_type, "message": error_message}
        encoded = JSON_ENCODE(response)
    payload = BASE64_ENCODE(encoded.encode("utf-8")).decode("ascii")
    marker = message["marker"] + ":" + str(status)
    stdout.write(marker + ":" + payload + "\n")
    stdout.flush()
    stderr.write(marker + "\n")
    stderr.flush()
