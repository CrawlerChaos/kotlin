// IGNORE_BACKEND: JVM_IR
fun box(): String {
    run {
        run {
            var x1 = 0
            run { ++x1 }
            if (x1 == 0) return "fail"
        }

        run {
            var x2 = 0
            run { x2++ }
            if (x2 == 0) return "fail"
        }
    }

    return "OK"
}


// Shared variable slots (x1, x2):
// 4 ILOAD 2
// 4 ISTORE 2

// Temporary variable slots for 'x2++' + store to fake index:
// 0 ILOAD 1
// 2 ISTORE 1

// 0 NEW
// 0 GETFIELD
// 0 PUTFIELD
