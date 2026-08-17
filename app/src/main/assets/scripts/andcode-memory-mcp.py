#!/usr/bin/env python3
"""
andcode-memory-mcp.py - On-Device Game & Android Memory MCP Server for yCode.
Allows AI coding agents (Antigravity, OpenCode, Claude Code) to inspect and edit Android process RAM,
scan Unreal Engine 4 roots (FNamePool, GUObjectArray, GWorld, AActors), and execute memory commands.
Communicates directly with the native mem_server.sh daemon on 127.0.0.1:8088 without PC dependency.
"""

import sys
import json
import socket
import subprocess
import time
import os
import struct

HOST = "127.0.0.1"
PORT = 8088
DAEMON_PATH = "/data/local/tmp/mem_server.sh"
DAEMON_LOG = "/data/local/tmp/mem_server.log"

def log(msg: str):
    sys.stderr.write(f"[MemoryMCP] {msg}\n")
    sys.stderr.flush()

class MemoryBridge:
    def __init__(self, host=HOST, port=PORT):
        self.host = host
        self.port = port
        self.sock = None

    def ensure_daemon_running(self) -> bool:
        if self.ping():
            return True
        log("Daemon not responding on port 8088. Attempting to start on-device daemon via root...")
        try:
            # 1. Ensure binary exists and has 0777 permissions
            subprocess.run(["su", "-c", f"chmod 777 {DAEMON_PATH}"], capture_output=True, timeout=3)
            # 2. Launch in background
            subprocess.run(
                ["su", "-c", f"nohup {DAEMON_PATH} > {DAEMON_LOG} 2>&1 &"],
                capture_output=True,
                timeout=3
            )
            time.sleep(0.5)
        except Exception as e:
            log(f"Auto-start daemon failed: {e}")
        return self.ping()

    def connect(self) -> bool:
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(5.0)
            s.connect((self.host, self.port))
            self.sock = s
            return True
        except Exception as e:
            self.sock = None
            return False

    def send_command(self, cmd_str: str, timeout: float = 8.0) -> dict:
        if not self.sock:
            if not self.connect():
                if not self.ensure_daemon_running() or not self.connect():
                    return {
                        "error": f"No se pudo conectar al daemon en {self.host}:{self.port}. Verifica que {DAEMON_PATH} tenga permisos root y esté corriendo."
                    }
        try:
            self.sock.settimeout(timeout)
            payload = (cmd_str.strip() + "\n").encode("utf-8")
            self.sock.sendall(payload)

            # Read line-delimited response
            buf = bytearray()
            while True:
                chunk = self.sock.recv(4096)
                if not chunk:
                    break
                buf.extend(chunk)
                if b"\n" in buf:
                    break

            line = buf.decode("utf-8", errors="ignore").strip()
            if not line:
                return {"error": "Empty response from daemon"}
            try:
                return json.loads(line)
            except Exception:
                return {"status": "raw", "data": line}
        except socket.timeout:
            log(f"Command timed out: {cmd_str}")
            self.sock = None
            return {"error": f"Timeout esperando respuesta del comando: {cmd_str}"}
        except Exception as e:
            log(f"Socket communication error: {e}")
            self.sock = None
            return {"error": str(e)}

    def ping(self) -> bool:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.5)
            s.connect((self.host, self.port))
            s.sendall(b"ping\n")
            res = s.recv(1024)
            s.close()
            return bool(res)
        except Exception:
            return False

bridge = MemoryBridge()

TOOLS = [
    {
        "name": "mem_status",
        "description": "Obtiene el estado de conexión del daemon de memoria nativo en Android y el proceso/juego actualmente vinculado.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "mem_attach",
        "description": "Vincula el daemon a un proceso de Android por nombre de paquete (ej. com.proximabeta.mf.liteuamo) o PID numérico.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Nombre de paquete o PID del proceso objetivo"
                }
            },
            "required": ["target"]
        }
    },
    {
        "name": "mem_list_processes",
        "description": "Lista todos los procesos y PIDs activos en el dispositivo Android con detección automática de juegos.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "mem_get_modules",
        "description": "Obtiene los módulos y librerías compartidas (.so como libUE4.so, libanogs.so) con direcciones base y tamaños en RAM.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "mem_ue4_roots",
        "description": "Resuelve las estructuras raíz de Unreal Engine 4 en memoria (lib_base, FNamePool, GUObjectArray, GWorld).",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "mem_scan_ue4_roots",
        "description": "Escanea dinámicamente instrucciones ARM64 para resolver raíces de UE4 sin requerir offsets fijos.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "mem_get_world_actors",
        "description": "Extrae entidades, jugadores (posiciones 3D X,Y,Z, HP, nombres) y actores activos de la partida en tiempo real.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "gworld_address": {
                    "type": "string",
                    "description": "Dirección hexadecimal opcional de GWorld"
                },
                "limit": {
                    "type": "number",
                    "description": "Límite máximo de actores a retornar (por defecto 512)"
                }
            }
        }
    },
    {
        "name": "mem_read_hex",
        "description": "Lee bytes en formato hexadecimal crudo desde una dirección de memoria del proceso.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "address": {
                    "type": "string",
                    "description": "Dirección hexadecimal (ej. 0x7d12340000)"
                },
                "size": {
                    "type": "number",
                    "description": "Cantidad de bytes a leer (por defecto 64)"
                }
            },
            "required": ["address"]
        }
    },
    {
        "name": "mem_read_types",
        "description": "Lee memoria y la formatea automáticamente en tipos primitivos: Int32, Int64, Float, Double, Pointer, ASCII.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "address": {
                    "type": "string",
                    "description": "Dirección hexadecimal a leer"
                },
                "size": {
                    "type": "number",
                    "description": "Cantidad de bytes a leer (4, 8, 16, 64)"
                }
            },
            "required": ["address"]
        }
    },
    {
        "name": "mem_read_pointer_chain",
        "description": "Sigue una cadena de punteros multinivel (ej. base 0x7d1000 con offsets [0x30, 0x180]).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "base_address": {
                    "type": "string",
                    "description": "Dirección base hexadecimal"
                },
                "offsets": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Lista de offsets hexadecimales (ej. ['0x30', '0x180'])"
                }
            },
            "required": ["base_address", "offsets"]
        }
    },
    {
        "name": "mem_read_string",
        "description": "Lee una cadena de texto terminada en null o formato FString desde la memoria.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "address": {
                    "type": "string",
                    "description": "Dirección de memoria"
                },
                "max_length": {
                    "type": "number",
                    "description": "Longitud máxima (por defecto 128)"
                }
            },
            "required": ["address"]
        }
    },
    {
        "name": "mem_write_hex",
        "description": "Escribe bytes en formato hexadecimal en la memoria del juego (requiere root).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "address": {
                    "type": "string",
                    "description": "Dirección hexadecimal de destino"
                },
                "hex_data": {
                    "type": "string",
                    "description": "Secuencia de bytes en hex (ej. '1F2003D5')"
                }
            },
            "required": ["address", "hex_data"]
        }
    },
    {
        "name": "mem_pattern_scan",
        "description": "Búsqueda AOB (Array of Bytes) de firmas en memoria con soporte de comodines ??.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "Patrón AOB (ej. '48 8B 05 ?? ?? ?? ??')"
                },
                "module": {
                    "type": "string",
                    "description": "Módulo donde buscar (ej. 'libUE4.so')"
                }
            },
            "required": ["pattern"]
        }
    },
    {
        "name": "mem_dump_fixed_elf",
        "description": "Vuelca y reconstruye una librería .so descifrada desde la RAM para análisis en Ghidra / IDA Pro.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "module": {
                    "type": "string",
                    "description": "Nombre del módulo (por defecto libUE4.so)"
                },
                "output_path": {
                    "type": "string",
                    "description": "Ruta de guardado (por defecto /sdcard/Documents/dump_libUE4.so)"
                }
            }
        }
    },
    {
        "name": "mem_start_daemon",
        "description": "Inicia el daemon nativo mem_server.sh en /data/local/tmp/ con privilegios de root.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "mem_stop_daemon",
        "description": "Detiene el daemon mem_server.sh y libera los puertos y sockets.",
        "inputSchema": {
            "type": "object",
            "properties": {}
        }
    },
    {
        "name": "mem_get_device_logs",
        "description": "Obtiene los logs de depuración del kernel y del daemon de memoria.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "limit": {"type": "number"}
            }
        }
    },
    {
        "name": "mem_fs_list",
        "description": "Lista archivos en /data/local/tmp o directorios del sistema de Android.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Ruta a inspeccionar (por defecto /data/local/tmp)"}
            }
        }
    }
]

def handle_tool_call(name: str, args: dict) -> dict:
    if name == "mem_status":
        return bridge.send_command("status")
    elif name == "mem_attach":
        target = args.get("target", "")
        return bridge.send_command(f"attach {target}")
    elif name == "mem_list_processes":
        return bridge.send_command("list_proc")
    elif name == "mem_get_modules":
        return bridge.send_command("modules")
    elif name == "mem_ue4_roots":
        return bridge.send_command("ue4_roots")
    elif name == "mem_scan_ue4_roots":
        return bridge.send_command("scan_ue4_roots")
    elif name == "mem_get_world_actors":
        gw = args.get("gworld_address", "")
        lim = args.get("limit", 512)
        cmd = f"get_actors {gw} {lim}".strip()
        return bridge.send_command(cmd, timeout=12.0)
    elif name == "mem_read_hex":
        addr = args.get("address", "")
        sz = args.get("size", 64)
        return bridge.send_command(f"read_hex {addr} {sz}")
    elif name == "mem_read_types":
        addr = args.get("address", "")
        sz = args.get("size", 16)
        raw = bridge.send_command(f"read_hex {addr} {sz}")
        if "hex" in raw:
            hex_str = raw["hex"]
            try:
                b = bytes.fromhex(hex_str)
                raw["int32_le"] = struct.unpack("<i", b[:4])[0] if len(b) >= 4 else None
                raw["uint32_le"] = struct.unpack("<I", b[:4])[0] if len(b) >= 4 else None
                raw["float_le"] = struct.unpack("<f", b[:4])[0] if len(b) >= 4 else None
                raw["int64_le"] = struct.unpack("<q", b[:8])[0] if len(b) >= 8 else None
                raw["uint64_le"] = hex(struct.unpack("<Q", b[:8])[0]) if len(b) >= 8 else None
                raw["double_le"] = struct.unpack("<d", b[:8])[0] if len(b) >= 8 else None
                raw["ascii"] = "".join(chr(c) if 32 <= c <= 126 else "." for c in b)
            except Exception as e:
                raw["parse_error"] = str(e)
        return raw
    elif name == "mem_read_pointer_chain":
        base = args.get("base_address", "")
        offsets = args.get("offsets", [])
        off_str = ",".join(offsets)
        return bridge.send_command(f"read_ptr_chain {base} {off_str}")
    elif name == "mem_read_string":
        addr = args.get("address", "")
        max_l = args.get("max_length", 128)
        return bridge.send_command(f"read_string {addr} {max_l}")
    elif name == "mem_write_hex":
        addr = args.get("address", "")
        data = args.get("hex_data", "")
        return bridge.send_command(f"write_hex {addr} {data}")
    elif name == "mem_pattern_scan":
        pat = args.get("pattern", "")
        mod = args.get("module", "")
        return bridge.send_command(f"pattern_scan {mod} {pat}".strip())
    elif name == "mem_dump_fixed_elf":
        mod = args.get("module", "libUE4.so")
        out = args.get("output_path", "/sdcard/Documents/dump_libUE4.so")
        return bridge.send_command(f"dump_elf {mod} {out}", timeout=30.0)
    elif name == "mem_start_daemon":
        ok = bridge.ensure_daemon_running()
        return {"success": ok, "message": "Daemon iniciado" if ok else "Fallo al iniciar daemon (verifica permisos root)"}
    elif name == "mem_stop_daemon":
        try:
            subprocess.run(["su", "-c", "pkill -f mem_server.sh"], capture_output=True)
            return {"success": True, "message": "Daemon detenido"}
        except Exception as e:
            return {"success": False, "error": str(e)}
    elif name == "mem_get_device_logs":
        lim = args.get("limit", 50)
        return bridge.send_command(f"get_logs {lim}")
    elif name == "mem_fs_list":
        p = args.get("path", "/data/local/tmp")
        return bridge.send_command(f"fs_list {p}")
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
                            "name": "andcode-memory-mcp",
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
