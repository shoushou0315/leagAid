package com.example.demo.service.lcu;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;
import java.util.List;

/**
 * 通过 NT 底层 API 读取进程命令行（复刻 League Akari / 阿卡丽助手的方案）
 *
 * 原理：ACE 反作弊拦截了标准API（ProcessHandle/WMI/Toolhelp）读取进程命令行，
 *       但未拦截 ntdll 的 NtQueryInformationProcess(类型60) 内核查询。
 *       本类通过 JNA 直接调用该底层API，绕过拦截。
 *
 * 注意：需要管理员权限运行（部分系统），且不保证所有ACE版本下都有效。
 */
public class LcuNtAuth {

    /** NtQueryInformationProcess 的 ProcessCommandLineInformation 类型 */
    private static final int PROCESS_COMMAND_LINE_INFORMATION = 60;
    /** PROCESS_QUERY_LIMITED_INFORMATION */
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    /** PROCESS_QUERY_INFORMATION */
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;

    // ntdll.NtQueryInformationProcess 签名
    // NTSTATUS NtQueryInformationProcess(
    //   HANDLE ProcessHandle, PROCESSINFOCLASS ProcessInformationClass,
    //   PVOID ProcessInformation, ULONG ProcessInformationLength,
    //   PULONG ReturnLength)
    private interface NtDll extends com.sun.jna.Library {
        int NtQueryInformationProcess(Pointer processHandle,
                                      int processInformationClass,
                                      Pointer processInformation,
                                      int processInformationLength,
                                      IntByReference returnLength);
    }

    private static final NtDll NTDLL = Native.load("ntdll", NtDll.class);

    // 进程信息结构（64位布局）
    // USHORT Length; USHORT MaximumLength; PWSTR Buffer;  → Buffer 在偏移8
    private static final int UNICODE_STRING_BUFFER_OFFSET = 8;

    private LcuNtAuth() {}

    /**
     * 读取指定PID进程的命令行。
     * @return 命令行字符串，失败返回 null
     */
    public static String getCommandLine(int pid) {
        // 先尝试组合权限打开进程
        WinNT.HANDLE h = Kernel32.INSTANCE.OpenProcess(
                PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_QUERY_INFORMATION,
                false, pid);
        if (h == null || h.getPointer() == null || Pointer.nativeValue(h.getPointer()) == 0) {
            return null;
        }
        Pointer handle = h.getPointer();
        try {
            // 第一次调用：获取缓冲区大小
            IntByReference returnLength = new IntByReference();
            int status = NTDLL.NtQueryInformationProcess(
                    handle, PROCESS_COMMAND_LINE_INFORMATION, null, 0, returnLength);
            // 预期返回 STATUS_BUFFER_TOO_SMALL (0xC0000023) 或类似，拿到需要的大小
            if (returnLength.getValue() <= 0) {
                return null;
            }

            // 第二次调用：真正读取
            Memory buffer = new Memory(returnLength.getValue());
            status = NTDLL.NtQueryInformationProcess(
                    handle, PROCESS_COMMAND_LINE_INFORMATION, buffer,
                    returnLength.getValue(), returnLength);
            if (status != 0) {
                return null;
            }

            // 解析 UNICODE_STRING：Buffer 指针在偏移8处
            Pointer bufferPtr = buffer.getPointer(UNICODE_STRING_BUFFER_OFFSET);
            if (bufferPtr == null || Pointer.nativeValue(bufferPtr) == 0) {
                return null;
            }
            return bufferPtr.getWideString(0);
        } finally {
            Kernel32.INSTANCE.CloseHandle(h);
        }
    }

    /**
     * 扫描 LeagueClientUx/LeagueClient 进程，读取其命令行。
     * @return 包含端口/token的认证信息列表
     */
    public static List<UxAuthInfo> findUxAuth() {
        List<UxAuthInfo> result = new ArrayList<>();
        List<Integer> pids = findPidsByName("LeagueClient");
        pids.addAll(findPidsByName("LeagueClientUx"));
        for (int pid : pids) {
            String cmd = getCommandLine(pid);
            if (cmd == null || cmd.isEmpty()) continue;
            if (verbose) System.out.println("  [Nt] PID " + pid + " 命令行: " + cmd);
            UxAuthInfo info = parseCommandLine(cmd);
            if (info != null) {
                info.pid = pid;
                result.add(info);
            }
        }
        return result;
    }

    private static boolean verbose = true;

    /** 设置是否打印详细进程信息 */
    public static void setVerbose(boolean v) { verbose = v; }

    /** 按进程名获取所有PID */
    private static List<Integer> findPidsByName(String name) {
        List<Integer> result = new ArrayList<>();
        // 用 ProcessHandle 枚举（这一步不涉及被拦截的API）
        ProcessHandle.allProcesses().forEach(p -> {
            String procName = p.info().command().orElse("");
            if (procName.toLowerCase().contains(name.toLowerCase())) {
                result.add((int) p.pid());
            }
        });
        return result;
    }

    /** 从命令行解析端口/token */
    public static UxAuthInfo parseCommandLine(String cmd) {
        String port = extractValue(cmd, "--app-port=");
        String token = extractValue(cmd, "--remoting-auth-token=");
        if (port == null || token == null) return null;
        UxAuthInfo info = new UxAuthInfo();
        info.port = Integer.parseInt(port);
        info.authToken = token;
        info.riotClientPort = parseIntOrZero(extractValue(cmd, "--riotclient-app-port="));
        info.riotClientAuthToken = extractValue(cmd, "--riotclient-auth-token=");
        return info;
    }

    private static String extractValue(String cmd, String key) {
        int idx = cmd.indexOf(key);
        if (idx < 0) return null;
        String rest = cmd.substring(idx + key.length());
        int end = rest.indexOf(' ');
        if (end < 0) end = rest.indexOf('"');
        if (end < 0) end = rest.length();
        return rest.substring(0, end).trim().replace("\"", "");
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    /** LCU认证信息 */
    public static class UxAuthInfo {
        public int pid;
        public int port;
        public String authToken;
        public int riotClientPort;
        public String riotClientAuthToken;

        @Override
        public String toString() {
            return String.format("UxAuthInfo{pid=%d, port=%d, token=%s, riotClientPort=%d, riotClientToken=%s}",
                    pid, port, authToken, riotClientPort, riotClientAuthToken);
        }
    }

    /** 测试入口 */
    public static void main(String[] args) {
        System.out.println("========== NtQueryInformationProcess 读取测试 ==========");
        System.out.println("[提示] 需管理员权限运行才能读取其他进程命令行");
        List<UxAuthInfo> infos = findUxAuth();
        if (infos.isEmpty()) {
            System.out.println("[结果] 未读取到 LeagueClient 的端口/token");
            System.out.println("       可能原因：1)未以管理员运行 2)ACE版本拦截了底层API");
        } else {
            for (UxAuthInfo info : infos) {
                System.out.println("[结果] " + info);
            }
        }
    }
}
