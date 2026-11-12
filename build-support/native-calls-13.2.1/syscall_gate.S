/* Preserve the original variadic ABI for non-network syscalls. No va_arg guessing. */
.text
.global fg_syscall_gate
.hidden fg_syscall_gate
.hidden fg_real_syscall
.hidden fg_blocked_syscall
#if defined(__aarch64__)
.type fg_syscall_gate,%function
fg_syscall_gate:
    cmp x0, #198
    b.lt 1f
    cmp x0, #212
    b.le fg_blocked_syscall
1:
    cmp x0, #242
    b.eq fg_blocked_syscall
    cmp x0, #243
    b.eq fg_blocked_syscall
    cmp x0, #269
    b.eq fg_blocked_syscall
    adrp x16, fg_real_syscall
    ldr x16, [x16, :lo12:fg_real_syscall]
    br x16
#elif defined(__x86_64__)
.type fg_syscall_gate,@function
fg_syscall_gate:
    cmp $41, %rdi
    jl 1f
    cmp $55, %rdi
    jle fg_blocked_syscall
1:
    cmp $288, %rdi
    je fg_blocked_syscall
    cmp $299, %rdi
    je fg_blocked_syscall
    cmp $307, %rdi
    je fg_blocked_syscall
    jmp *fg_real_syscall(%rip)
#else
#error Unsupported architecture: socket gate supports arm64 and x86_64 only
#endif
.section .note.GNU-stack,"",%progbits
