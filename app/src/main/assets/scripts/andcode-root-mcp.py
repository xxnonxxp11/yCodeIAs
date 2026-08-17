#!/usr/bin/env python3
"""
andcode-root-mcp.py - On-Device Root & Magisk/KernelSU/APatch Tool Bridge for yCode.
Allows AI coding agents to execute commands with superuser privileges (su),
run scripts and binaries in /data/local/tmp/, read/write protected files, and inspect system state.
"""

import sys
import json
import subprocess
import os

def log(msg: str):
    sys.stderr.write(f"[RootMCP] {msg}\n")
    sys.stderr.flush()

def is_root_available() -> dict:
    su_paths = [
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "su"
    ]
    for p in su_paths:
        try:
            res = subprocess.run([p, "-c", "id"], capture_output=True, text=True, timeout=2)
            if res.returncode == 0 and "uid=0" in res.stdout:
                return {"available": True, "su_path": p, "uid": res.stdout.strip()}
        except Exception:
            continue
    return {"available": False, "message": "su binary not found or root permission denied"}

TOOLS = [
    {
        "name": "root_status",
        "description": "Verifica si el dispositivo Android tiene acceso Root (Magisk, KernelSU, APatch) y permisos de superusuario concedidos.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "root_exec",
        "description": "Ejecuta un comando shell en el sistema operativo Android con privilegios de root (su -c).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "Comando shell a ejecutar con root"
                },
                "timeout_seconds": {
                    "type": "number",
                    "description": "Tiempo límite en segundos (por defecto 30)"
                }
            },
            "required": ["command"]
        }
    },
    {
        "name": "root_sh",
        "description": "Ejecuta un script .sh o binario ELF ubicado en /data/local/tmp/ con permisos root.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "script_path": {
                    "type": "string",
                    "description": "Ruta al script o binario (ej. /data/local/tmp/mi_script.sh)"
                },
                "args": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Argumentos para el script"
                }
            },
            "required": ["script_path"]
        }
    },
    {
        "name": "root_read_file",
        "description": "Lee el contenido de un archivo del sistema o ruta protegida (ej. /proc/cpuinfo, /data/local/tmp/log.txt) usando root.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Ruta absoluta del archivo a leer"
                },
                "max_lines": {
                    "type": "number",
                    "description": "Número máximo de líneas a leer (por defecto 500)"
                }
            },
            "required": ["path"]
        }
    },
    {
        "name": "root_write_file",
        "description": "Escribe o crea un archivo en rutas protegidas como /data/local/tmp/ con privilegios de root.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Ruta absoluta de destino"
                },
                "content": {
                    "type": "string",
                    "description": "Contenido de texto a escribir"
                },
                "chmod": {
                    "type": "string",
                    "description": "Permisos octales opcionales (ej. '777', '755')"
                }
            },
            "required": ["path", "content"]
        }
    },
    {
        "name": "root_list_dir",
        "description": "Lista el contenido de directorios del sistema protegidos (ej. /data/local/tmp, /data/data) con root.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Ruta del directorio a listar"
                }
            },
            "required": ["path"]
        }
    }
]

def handle_tool_call(name: str, args: dict) -> dict:
    if name == "root_status":
        return is_root_available()
    elif name == "root_exec":
        cmd = args.get("command", "")
        timeout = args.get("timeout_seconds", 30)
        try:
            res = subprocess.run(["su", "-c", cmd], capture_output=True, text=True, timeout=timeout)
            return {
                "exit_code": res.returncode,
                "stdout": res.stdout,
                "stderr": res.stderr
            }
        except subprocess.TimeoutExpired:
            return {"error": f"Comando excedió el tiempo límite de {timeout}s"}
        except Exception as e:
            return {"error": str(e)}
    elif name == "root_sh":
        spath = args.get("script_path", "")
        extra_args = " ".join(args.get("args", []))
        full_cmd = f"chmod +x {spath} && {spath} {extra_args}".strip()
        try:
            res = subprocess.run(["su", "-c", full_cmd], capture_output=True, text=True, timeout=60)
            return {
                "exit_code": res.returncode,
                "stdout": res.stdout,
                "stderr": res.stderr
            }
        except Exception as e:
            return {"error": str(e)}
    elif name == "root_read_file":
        fpath = args.get("path", "")
        max_lines = args.get("max_lines", 500)
        cmd = f"head -n {max_lines} {fpath}"
        try:
            res = subprocess.run(["su", "-c", cmd], capture_output=True, text=True, timeout=10)
            return {
                "exit_code": res.returncode,
                "content": res.stdout,
                "stderr": res.stderr
            }
        except Exception as e:
            return {"error": str(e)}
    elif name == "root_write_file":
        fpath = args.get("path", "")
        content = args.get("content", "")
        chmod_val = args.get("chmod", "644")
        try:
            # Write via stdin to su -c tee
            p = subprocess.Popen(["su", "-c", f"tee {fpath} > /dev/null && chmod {chmod_val} {fpath}"],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            stdout, stderr = p.communicate(input=content, timeout=10)
            return {
                "success": p.returncode == 0,
                "exit_code": p.returncode,
                "path": fpath,
                "stderr": stderr
            }
        except Exception as e:
            return {"error": str(e)}
    elif name == "root_list_dir":
        dpath = args.get("path", "/data/local/tmp")
        cmd = f"ls -la {dpath}"
        try:
            res = subprocess.run(["su", "-c", cmd], capture_output=True, text=True, timeout=10)
            return {
                "exit_code": res.returncode,
                "listing": res.stdout,
                "stderr": res.stderr
            }
        except Exception as e:
            return {"error": str(e)}
    else:
        return {"error": f"Herramienta desconocida: {name}"}

def main():
    while True:
        try:
            line = sys.stdin.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            req = json.loads(line)
            req_id = req.get("id")
            method = req.get("method")

            if method == "initialize":
                resp = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "protocolVersion": "2024-11-05",
                        "serverInfo": {
                            "name": "andcode-root-mcp",
                            "version": "1.0.0"
                        },
                        "capabilities": {
                            "tools": {}
                        }
                    }
                }
                sys.stdout.write(json.dumps(resp) + "\n")
                sys.stdout.flush()
            elif method == "tools/list":
                resp = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "tools": TOOLS
                    }
                }
                sys.stdout.write(json.dumps(resp) + "\n")
                sys.stdout.flush()
            elif method == "tools/call":
                params = req.get("params", {})
                t_name = params.get("name")
                t_args = params.get("arguments", {})
                res = handle_tool_call(t_name, t_args)
                resp = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "content": [
                            {
                                "type": "text",
                                "text": json.dumps(res, indent=2, ensure_ascii=False)
                            }
                        ]
                    }
                }
                sys.stdout.write(json.dumps(resp) + "\n")
                sys.stdout.flush()
            else:
                resp = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {
                        "code": -32601,
                        "message": f"Method {method} not found"
                    }
                }
                sys.stdout.write(json.dumps(resp) + "\n")
                sys.stdout.flush()
        except Exception as e:
            log(f"Error handling JSON-RPC: {e}")

if __name__ == "__main__":
    main()
