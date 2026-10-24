#!/usr/bin/env python3
# Copyright (C) 2026 小狗便利店投屏 contributors; SPDX-License-Identifier: GPL-3.0-only
"""Build pinned OpenSSL and libplist sources for the mirror experiment (Python 3.12+)."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,tarfile
module=Path(__file__).resolve().parents[1]
sdk=Path(os.environ['ANDROID_HOME']);ndk=sdk/'ndk/28.2.13676358';cmake=sdk/'cmake/3.22.1/bin/cmake'
llvm=ndk/'toolchains/llvm/prebuilt/darwin-x86_64' if os.uname().sysname=='Darwin' else ndk/'toolchains/llvm/prebuilt/linux-x86_64'
work=module/'.native-build';work.mkdir(exist_ok=True)
for name,digest in json.loads((module/'vendor/sources/SHA256SUMS.json').read_text()).items():
 archive=module/'vendor/sources'/name;assert hashlib.sha256(archive.read_bytes()).hexdigest()==digest,name
 dest=work/('openssl-3.0.22' if name.startswith('openssl') else 'libplist-2.6.0')
 if not dest.exists():
  with tarfile.open(archive) as t:t.extractall(work,filter='data')
def run(*args,cwd=None,env=None):subprocess.run([str(x) for x in args],cwd=cwd,env=env,check=True)
# libplist's plain C sources do not need autotools or its unused C++ bindings.
plist=work/'libplist-cmake';plist.mkdir(exist_ok=True)
sources='base64 bplist bytearray hashtable jplist jsmn oplist out-default out-limd out-plutil plist ptrarray time64 xplist'
cmake_text='cmake_minimum_required(VERSION 3.22)\nproject(plist C)\nset(CMAKE_POSITION_INDEPENDENT_CODE ON)\n'
cmake_text+='set(PLIST "'+str(work/'libplist-2.6.0')+'")\nadd_library(plist STATIC\n'
cmake_text+=''.join(' "${PLIST}/src/'+x+'.c"\n' for x in sources.split())
cmake_text+=' "${PLIST}/libcnary/node.c" "${PLIST}/libcnary/node_list.c")\n'
cmake_text+='target_include_directories(plist PRIVATE "${PLIST}/src" "${PLIST}/include" "${PLIST}/libcnary/include")\ntarget_compile_definitions(plist PRIVATE _GNU_SOURCE HAVE_STRNDUP PACKAGE_VERSION="2.6.0")\n'
(plist/'CMakeLists.txt').write_text(cmake_text)
for abi,target in [('arm64-v8a','android-arm64'),('armeabi-v7a','android-arm')]:
 out=module/'src/main/cpp/prebuilt'/abi;(out/'lib').mkdir(parents=True,exist_ok=True);(out/'include').mkdir(exist_ok=True)
 if not (out/'lib/libcrypto.a').exists():
  build=work/('openssl-'+abi)
  if not build.exists():shutil.copytree(work/'openssl-3.0.22',build)
  env=dict(os.environ,ANDROID_NDK_ROOT=str(ndk),PATH=str(llvm/'bin')+os.pathsep+os.environ['PATH'])
  run('./Configure',target,'no-shared','no-tests','no-engine','no-dso','no-ui-console','no-comp','no-filenames','-D__ANDROID_API__=24','--prefix=/usr/local/puppy-mirror',cwd=build,env=env)
  run('make','-j6','build_libs',cwd=build,env=env)
  shutil.copy2(build/'libcrypto.a',out/'lib/libcrypto.a');shutil.copytree(build/'include/openssl',out/'include/openssl',dirs_exist_ok=True)
 if not (out/'lib/libplist-2.0.a').exists():
  build=work/('plist-'+abi)
  run(cmake,'-S',plist,'-B',build,'-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI='+abi,'-DANDROID_PLATFORM=android-24','-DCMAKE_BUILD_TYPE=Release','-G','Ninja','-DCMAKE_MAKE_PROGRAM='+str(cmake.parent/'ninja'))
  run(cmake,'--build',build,'--parallel','6')
  shutil.copy2(build/'libplist.a',out/'lib/libplist-2.0.a');shutil.copytree(work/'libplist-2.6.0/include/plist',out/'include/plist',dirs_exist_ok=True)
 print('NATIVE_DEPS_OK',abi,flush=True)
