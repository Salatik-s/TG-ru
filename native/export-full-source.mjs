import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { join } from 'node:path';
import { here, verifySource, patchSocket } from './prepare.mjs';

// Export only a reviewable overlay under native/. The caller applies it to a
// separate full upstream checkout; this script never edits that checkout.
const source=join(here,'upstream'),out=join(here,'full-source-overlay/TMessagesProj/jni');
verifySource(source);
mkdirSync(join(out,'tgnet'),{recursive:true});
const original=join(source,'TMessagesProj/jni');
writeFileSync(join(out,'tgnet/ConnectionSocket.cpp'),patchSocket(readFileSync(join(original,'tgnet/ConnectionSocket.cpp'),'utf8')));
for(const name of ['FamilyTransport.h','FamilyTransport.cpp','FamilyJni.cpp']) copyFileSync(join(here,name),join(out,name));
let jni=readFileSync(join(original,'jni.c'),'utf8');
const declaration='int registerNativeTgNetFunctions(JavaVM *vm, JNIEnv *env);';
if(!jni.includes(declaration)) throw Error('JNI declaration anchor changed');
jni=jni.replace(declaration,declaration+'\nint registerFamilyNativeFunctions(JavaVM *vm, JNIEnv *env);');
const register='    if (registerNativeTgNetFunctions(vm, env) != JNI_TRUE) {\n        return -1;\n    }';
if(!jni.includes(register)) throw Error('JNI registration anchor changed');
jni=jni.replace(register,register+'\n\n    if (registerFamilyNativeFunctions(vm, env) != JNI_TRUE) {\n        return -1;\n    }');
writeFileSync(join(out,'jni.c'),jni);
let cmake=readFileSync(join(original,'CMakeLists.txt'),'utf8');
cmake+='\n# FamilyGram: same-library tgnet replacement, no JNI re-registration boundary.\n';
cmake+='target_sources(${NATIVE_LIB} PRIVATE FamilyTransport.cpp FamilyJni.cpp)\n';
cmake+='target_compile_definitions(${NATIVE_LIB} PRIVATE FAMILYGRAM_FULL_SOURCE)\n';
cmake+='target_include_directories(tgnet PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})\n';
cmake+='target_link_libraries(${NATIVE_LIB} dl)\n';
writeFileSync(join(out,'CMakeLists.txt'),cmake);
console.log(`Full-source fallback overlay prepared at ${out}`);
