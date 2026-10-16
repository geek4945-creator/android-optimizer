package dev.optimizer.android.core;

// Runs inside the Shizuku "user service" process (adb shell uid 2000, or root if Shizuku was started by root).
interface IUserService {
    void destroy() = 16777114; // reserved transaction code used by Shizuku
    void exit() = 1;
    // returns [exitCode, stdout, stderr]
    String[] exec(String command, int timeoutSec) = 2;
}
