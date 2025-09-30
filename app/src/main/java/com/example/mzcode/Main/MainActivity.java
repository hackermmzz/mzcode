package com.example.mzcode.Main;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mzcode.R;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    // 视图组件
    private EditText terminal;
    private ScrollView scrollView;

    // 终端状态变量
    private List<String> commandHistory = new ArrayList<>();
    private int historyPosition = 0;
    private String currentWorkingDirectory = "~";
    private int promptLength = 0;
    private boolean isProcessingOutput = false;

    // 终端颜色配置（ANSI颜色码 -> Android Color）
    private Map<String, Integer> colorMap = new HashMap<>();

    // SSH连接信息（实际应用需通过弹窗输入密码，避免硬编码）
    private final String username = "root";
    private final String host = "47.113.185.25";
    private final int port = 22;
    private String password;

    // SSH核心对象
    private Session session;
    private ChannelExec channel;
    private OutputStream outputStream;

    // UI线程工具
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpannableStringBuilder stringBuilder = new SpannableStringBuilder();
    private int currentTextColor = Color.WHITE;

    // 异步任务线程池（替代Kotlin协程处理IO操作）
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ssh_terminal);

        // 初始化视图
        terminal = findViewById(R.id.terminal);
        scrollView = findViewById(R.id.scrollView);

        // 初始化颜色映射
        initColorMap();

        // 配置终端基础属性
        setupTerminal();

        // 临时硬编码密码（实际需通过Dialog获取）
        password = "01190650asd";

        // 连接SSH服务器
        connectToSshServer();

        // 配置输入事件处理（键盘、输入法）
        setupInputHandling();
    }

    /**
     * 初始化ANSI颜色码与Android Color的映射
     */
    private void initColorMap() {
        colorMap.put("30", Color.BLACK);
        colorMap.put("31", Color.RED);
        colorMap.put("32", Color.GREEN);
        colorMap.put("33", Color.YELLOW);
        colorMap.put("34", Color.BLUE);
        colorMap.put("35", Color.MAGENTA);
        colorMap.put("36", Color.CYAN);
        colorMap.put("37", Color.WHITE);
        colorMap.put("90", Color.DKGRAY);
        colorMap.put("91", Color.RED);
        colorMap.put("92", Color.GREEN);
        colorMap.put("93", Color.YELLOW);
        colorMap.put("94", Color.BLUE);
        colorMap.put("95", Color.MAGENTA);
        colorMap.put("96", Color.CYAN);
        colorMap.put("97", Color.WHITE);
    }

    /**
     * 配置终端基础行为（光标、选择等）
     */
    private void setupTerminal() {
        terminal.setTextIsSelectable(false); // 禁止文本选择（避免干扰输入）
        terminal.setCursorVisible(true);     // 显示光标
        terminal.requestFocus();             // 获取焦点
    }

    /**
     * 异步连接SSH服务器
     */
    private void connectToSshServer() {
        appendToTerminal("Connecting to " + username + "@" + host + ":" + port + "...\n");

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JSch jsch = new JSch();

                    // 1. 创建SSH会话
                    session = jsch.getSession(username, host, port);
                    session.setPassword(password);
                    // 跳过主机密钥校验（生产环境需改为"yes"并处理密钥）
                    session.setConfig("StrictHostKeyChecking", "no");
                    // 设置终端窗口大小
                    session.setConfig("COLUMNS", "120");
                    session.setConfig("LINES", "30");
                    session.connect(5000); // 5秒连接超时

                    // 2. 打开执行通道（交互式bash）
                    channel = (ChannelExec) session.openChannel("exec");
                    channel.setCommand("export TERM=xterm-256color; bash -i"); // 支持256色与交互

                    // 3. 获取输入输出流
                    final InputStream inputStream = channel.getInputStream();  // 服务器标准输出
                    final InputStream errorStream = channel.getErrStream();    // 服务器错误输出
                    outputStream = channel.getOutputStream();                  // 客户端输入流

                    // 4. 启动通道
                    channel.connect();

                    // 5. UI线程提示连接成功
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendToTerminal("Connected successfully!\n");
                            showPrompt(); // 显示命令提示符
                        }
                    });

                    // 6. 异步读取服务器输出（标准输出+错误输出）
                    readStream(inputStream, false);
                    readStream(errorStream, true);

                } catch (final Exception e) {
                    // 连接异常处理（UI线程更新）
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendToTerminal("\nConnection failed: " + e.getMessage() + "\n");
                        }
                    });
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 读取SSH服务器输出流（标准输出/错误输出）
     * @param inputStream 待读取的流
     * @param isError 是否为错误流
     */
    private void readStream(final InputStream inputStream, final boolean isError) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                if (inputStream == null) return;

                byte[] buffer = new byte[1024];
                int bytesRead;
                try {
                    // 循环读取流（通道连接时持续读取）
                    while (channel != null && channel.isConnected()
                            && (bytesRead = inputStream.read(buffer)) != -1) {
                        final String output = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                        // UI线程处理输出（避免跨线程操作UI）
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                processAndAppendOutput(output, isError);
                            }
                        });
                    }
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appendToTerminal("\nError reading stream: " + e.getMessage() + "\n");
                        }
                    });
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 处理服务器输出（解析ANSI转义、清屏等）并追加到终端
     */
    private void processAndAppendOutput(String output, boolean isError) {
        isProcessingOutput = true;
        String processedOutput = output;

        // 1. 处理清屏命令（ANSI清屏序列：\u001B[2J\u001B[1;1H）
        if (processedOutput.contains("\u001B[2J\u001B[1;1H")) {
            clearTerminal();
            processedOutput = processedOutput.replace("\u001B[2J\u001B[1;1H", "");
        }

        // 2. 处理ANSI颜色转义序列
        processAnsiEscapeCodes(processedOutput);

        // 3. 提取当前工作目录（从提示符中解析）
        extractWorkingDirectory(processedOutput);

        // 4. 更新终端显示与光标位置
        terminal.setText(stringBuilder);
        terminal.setSelection(stringBuilder.length()); // 光标始终在末尾

        // 5. 滚动到底部
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });

        isProcessingOutput = false;
    }

    /**
     * 解析ANSI颜色转义序列（如\u001B[32m表示绿色）
     */
    private void processAnsiEscapeCodes(String text) {
        // 正则匹配ANSI转义序列：\u001B[数字;...m
        Pattern pattern = Pattern.compile("\u001B\\[(\\d+;?)+m");
        Matcher matcher = pattern.matcher(text);

        int currentIndex = 0;
        while (matcher.find()) {
            // 1. 追加匹配前的普通文本（带当前颜色）
            int matchStart = matcher.start();
            int matchEnd = matcher.end();
            if (currentIndex < matchStart) {
                String plainText = text.substring(currentIndex, matchStart);
                if (!plainText.isEmpty()) {
                    int start = stringBuilder.length();
                    stringBuilder.append(plainText);
                    // 设置文本颜色Span
                    stringBuilder.setSpan(
                            new ForegroundColorSpan(currentTextColor),
                            start,
                            stringBuilder.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            }

            // 2. 解析颜色码并更新当前颜色
            String codeStr = matcher.group().substring(2, matcher.group().length() - 1); // 截取颜色码（去掉\u001B[和m）
            if ("0".equals(codeStr)) {
                currentTextColor = Color.WHITE; // 重置颜色
            } else if (colorMap.containsKey(codeStr)) {
                currentTextColor = colorMap.get(codeStr); // 从映射获取颜色
            }

            currentIndex = matchEnd;
        }

        // 3. 追加剩余文本（带当前颜色）
        if (currentIndex < text.length()) {
            String remainingText = text.substring(currentIndex);
            if (!remainingText.isEmpty()) {
                int start = stringBuilder.length();
                stringBuilder.append(remainingText);
                stringBuilder.setSpan(
                        new ForegroundColorSpan(currentTextColor),
                        start,
                        stringBuilder.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
        }
    }

    /**
     * 从服务器输出中提取当前工作目录（解析提示符格式：username@host:dir# ）
     */
    private void extractWorkingDirectory(String output) {
        Pattern pattern = Pattern.compile(username + "@[^:]+:(.*?)[#\\$] ");
        Matcher matcher = pattern.matcher(output);
        if (matcher.find() && matcher.groupCount() > 0) {
            String dir = matcher.group(1);
            if (dir != null && !dir.isEmpty()) {
                currentWorkingDirectory = dir;
            }
        }
    }

    /**
     * 配置输入事件处理（键盘按键、输入法提交）
     */
    private void setupInputHandling() {
        Context context=this;
        // 1. 键盘按键监听
        terminal.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                // 只处理按键"按下"事件，且输出处理中不响应输入
                if (event.getAction() != KeyEvent.ACTION_DOWN || isProcessingOutput) {
                    return true;
                }

                String currentText = stringBuilder.toString();
                int cursorPos = terminal.getSelectionStart();

                // 禁止在提示符前编辑（光标强制在提示符后）
                if (cursorPos < promptLength) {
                    terminal.setSelection(stringBuilder.length());
                    return true;
                }

                // 处理具体按键
                switch (keyCode) {
                    case KeyEvent.KEYCODE_ENTER:

                        handleEnterKey(); // 回车：执行命令
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        navigateHistory(-1); // 上键：历史命令上翻
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        navigateHistory(1);  // 下键：历史命令下翻
                        return true;
                    case KeyEvent.KEYCODE_TAB:
                        handleTabCompletion(); // Tab：命令补全
                        return true;
                    case KeyEvent.KEYCODE_C:
                        if (event.isCtrlPressed()) {
                            sendCtrlC(); // Ctrl+C：中断命令
                            return true;
                        }
                        break;
                    case KeyEvent.KEYCODE_D:
                        if (event.isCtrlPressed()) {
                            sendCtrlD(); // Ctrl+D：退出当前shell
                            return true;
                        }
                        break;
                    case KeyEvent.KEYCODE_U:
                        if (event.isCtrlPressed()) {
                            clearCurrentLine(); // Ctrl+U：清空当前行
                            return true;
                        }
                        break;

                }
                return false;
            }
        });

        // 2. 输入法提交监听（如"完成"按钮）
        terminal.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    handleEnterKey();
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 处理回车键（执行命令）
     */
    private void handleEnterKey() {
        String currentText = stringBuilder.toString();
        final String command = currentText.substring(promptLength).trim();

        // 追加换行符
        stringBuilder.append("\n");

        if (!command.isEmpty()) {
            // 1. 保存命令历史（避免重复添加）
            if (commandHistory.isEmpty() || !commandHistory.get(commandHistory.size() - 1).equals(command)) {
                commandHistory.add(command);
            }
            historyPosition = commandHistory.size();

            // 2. 处理本地命令（无需发送到服务器）
            if ("clear".equals(command) || "cls".equals(command)) {
                clearTerminal();
                showPrompt();
                return;
            }

            // 3. 异步发送命令到SSH服务器
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (outputStream != null) {
                            outputStream.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                            outputStream.flush(); // 强制发送
                        }
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                appendToTerminal("\nFailed to send command: " + e.getMessage() + "\n");
                                showPrompt();
                            }
                        });
                        e.printStackTrace();
                    }
                }
            });
        } else {
            // 空命令：只显示新提示符
            showPrompt();
        }
    }

    /**
     * 导航命令历史（上翻/下翻）
     * @param direction 方向：-1=上翻，1=下翻
     */
    private void navigateHistory(int direction) {
        if (commandHistory.isEmpty()) return;

        int newPosition = historyPosition + direction;
        // 边界校验
        if (newPosition >= 0 && newPosition < commandHistory.size()) {
            historyPosition = newPosition;
            replaceCurrentCommand(commandHistory.get(newPosition));
        } else if (newPosition == commandHistory.size()) {
            // 回到空命令
            historyPosition = newPosition;
            replaceCurrentCommand("");
        }
    }

    /**
     * 替换当前命令（用于历史导航、Tab补全）
     */
    private void replaceCurrentCommand(String newCommand) {
        // 删除当前命令（从提示符后到末尾）
        stringBuilder.delete(promptLength, stringBuilder.length());
        // 追加新命令
        stringBuilder.append(newCommand);
        // 更新显示与光标位置
        terminal.setText(stringBuilder);
        terminal.setSelection(stringBuilder.length());
    }

    /**
     * 处理Tab补全（简单预设命令补全）
     */
    private void handleTabCompletion() {
        String currentText = stringBuilder.toString();
        String currentCommand = currentText.substring(promptLength);

        if (!currentCommand.isEmpty()) {
            // 预设支持补全的命令列表
            List<String> suggestions = Arrays.asList(
                    "ls", "cd", "pwd", "mkdir", "rm", "cp", "mv", "sudo",
                    "apt", "cat", "nano", "vim", "chmod", "chown"
            );

            // 筛选匹配的命令
            List<String> matches = new ArrayList<>();
            for (String cmd : suggestions) {
                if (cmd.startsWith(currentCommand)) {
                    matches.add(cmd);
                }
            }

            // 处理匹配结果
            if (matches.size() == 1) {
                // 唯一匹配：直接替换
                replaceCurrentCommand(matches.get(0));
            } else if (matches.size() > 1) {
                // 多个匹配：显示匹配列表
                stringBuilder.append("\n");
                for (int i = 0; i < matches.size(); i++) {
                    stringBuilder.append(matches.get(i));
                    if (i != matches.size() - 1) {
                        stringBuilder.append("  "); // 命令间用两个空格分隔
                    }
                }
                terminal.setText(stringBuilder);
                // 重新显示提示符并恢复当前输入
                showPrompt();
                replaceCurrentCommand(currentCommand);
            }
        }
    }

    /**
     * 发送Ctrl+C（中断当前命令，ASCII码3）
     */
    private void sendCtrlC() {
        try {
            if (outputStream != null) {
                outputStream.write(3);
                outputStream.flush();
                stringBuilder.append("^C\n");
                terminal.setText(stringBuilder);
                showPrompt();
            }
        } catch (Exception e) {
            appendToTerminal("\nFailed to send Ctrl+C: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    /**
     * 发送Ctrl+D（退出当前shell，ASCII码4）
     */
    private void sendCtrlD() {
        try {
            if (outputStream != null) {
                outputStream.write(4);
                outputStream.flush();
            }
        } catch (Exception e) {
            appendToTerminal("\nFailed to send Ctrl+D: " + e.getMessage() + "\n");
            e.printStackTrace();
        }
    }

    /**
     * 清空当前行（Ctrl+U）
     */
    private void clearCurrentLine() {
        stringBuilder.delete(promptLength, stringBuilder.length());
        terminal.setText(stringBuilder);
        terminal.setSelection(stringBuilder.length());
    }

    /**
     * 显示命令提示符（格式：username@host:dir# ）
     */
    private void showPrompt() {
        String prompt = username + "@" + host + ":" + currentWorkingDirectory + "# ";
        appendToTerminal(prompt);
        promptLength = stringBuilder.length(); // 记录提示符长度（用于后续编辑边界控制）
    }

    /**
     * 追加文本到终端（带颜色处理）
     */
    private void appendToTerminal(String text) {
        processAnsiEscapeCodes(text);
        terminal.setText(stringBuilder);
        terminal.setSelection(stringBuilder.length());
        // 滚动到底部
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    /**
     * 清空终端
     */
    private void clearTerminal() {
        stringBuilder.clear();
        currentTextColor = Color.WHITE; // 重置颜色
        terminal.setText(stringBuilder);
    }

    /**
     * 页面销毁时释放资源（SSH连接、线程池）
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 断开SSH连接
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        // 关闭线程池
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    /**
     * 拦截返回键（二次确认退出/清空当前命令）
     */
    @Override
    public void onBackPressed() {
        String currentText = stringBuilder.toString();
        String currentCommand = currentText.substring(promptLength);

        if (currentCommand.isEmpty()) {
            // 空命令：二次确认退出
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // 延迟2秒重置状态（避免快速按两次误退出）
                }
            }, 2000);
        } else {
            // 非空命令：清空当前行
            clearCurrentLine();
        }
    }
}