# talloc (vendored)

A hierarchical, reference-counted memory allocator from the Samba project, used
here only as a dependency of `proot` (see `thirdparty/proot`, a git submodule).

Unlike `llama.cpp` and `proot`, talloc is **not** a submodule — only these four
files are copied into this repository:

| File | Purpose |
|---|---|
| `talloc.c` | implementation |
| `talloc.h` | public API |
| `config.h` | minimal build configuration for the Android NDK cross-build |
| `replace.h` | shim for the Samba portability layer talloc normally links against |

## License

**LGPL-3.0-or-later** — see the per-file notices at the top of `talloc.c` and
`talloc.h`, plus the full texts in this directory:

- `COPYING.LESSER` — GNU Lesser General Public License v3.0
- `COPYING` — GNU General Public License v3.0 (LGPL-3.0 is defined as a set of
  additional permissions on top of it, so both texts are required)

`libtalloc.so` is built as a **shared library** (`build-proot.sh` passes
`-Wl,-soname,libtalloc.so`) and loaded dynamically by the proot binary, so the
relinking rights of LGPL-3.0 §4(d)(0) are satisfied: the library can be replaced
without rebuilding the rest of the application.

Upstream: <https://gitlab.com/samba-team/samba/-/tree/master/lib/talloc>
