"""Compile the embedded world HLSL with the same Windows compiler as the plugin."""
from pathlib import Path
import ctypes as c
import re

source = (Path(__file__).parent.parent / 'skse/src/WorldRender.cpp').read_text()
start = source.index('constexpr char kShader[]')
end = source.index(')";', start) + 3
shader = ''.join(re.findall(r'R"\((.*?)\)"', source[start:end], re.S)).encode()
compiler = c.WinDLL('d3dcompiler_47').D3DCompile
compiler.argtypes = [c.c_void_p, c.c_size_t, c.c_char_p, c.c_void_p, c.c_void_p,
                     c.c_char_p, c.c_char_p, c.c_uint, c.c_uint,
                     c.POINTER(c.c_void_p), c.POINTER(c.c_void_p)]
compiler.restype = c.c_long

def method(blob, index, result):
    table = c.cast(blob, c.POINTER(c.POINTER(c.c_void_p))).contents
    return c.WINFUNCTYPE(result, c.c_void_p)(table[index])(blob)

for entry, target in [('VSMain', 'vs_5_0'), ('PSMain', 'ps_5_0')]:
    code, errors = c.c_void_p(), c.c_void_p()
    hr = compiler(shader, len(shader), b'skycraft_world', None, None,
                  entry.encode(), target.encode(), 1 << 15, 0,
                  c.byref(code), c.byref(errors))
    if errors:
        print(c.string_at(method(errors, 3, c.c_void_p),
                          method(errors, 4, c.c_size_t)).decode(errors='replace'))
        method(errors, 2, c.c_uint)
    if code:
        method(code, 2, c.c_uint)
    if hr < 0:
        raise SystemExit(f'{entry} failed: {hr:#x}')
    print(entry + ' compiled successfully')
