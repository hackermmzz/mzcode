package com.example.mzcode.Terminal;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.example.mzcode.Config.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class SSHConnect {
    //定义通道类型
    enum ChannelType{
        SHELL,
        EXEC,
        SFTP,
    };
    //
    Session session=null;
    public SSHConnect(String user,String ip,int port,String passwd) throws Exception{
        JSch jsch = new JSch();
        try {
            session=jsch.getSession(user, ip, port);
            session.setPassword(passwd);
            session.setConfig("StrictHostKeyChecking", "no"); // 跳过主机密钥检查
            session.connect();
        } catch (JSchException e) {
            throw new RuntimeException(e);
        }
    }
    //打开一个通道
    public Object CreateChannel(ChannelType type)throws Exception{
        if(type==ChannelType.SHELL) return (ChannelShell)session.openChannel("shell");
        else if(type==ChannelType.EXEC)return (ChannelExec)session.openChannel("exec");
        else if(type==ChannelType.SFTP)return (ChannelSftp)session.openChannel("sftp");
        throw new Exception("类型错误");
    }
    //从管道读取输出
    public byte[] Read(Channel channel)throws Exception{
        InputStream in = channel.getInputStream();
        byte [] buffer=new byte[Config.SSHReadBufferSize];
        ByteArrayOutputStream ret=new ByteArrayOutputStream(Config.SSHReadBufferSize);
        int byteseed=0;
        while((byteseed=in.read(buffer))!=-1){
            ret.write(buffer,0,byteseed);
        }
        return ret.toByteArray();
    }
    //输入管道
    public void Write(Channel channel,byte [] data)throws Exception{
        OutputStream out=channel.getOutputStream();
        out.write(data);
        out.flush();
    }
    //输入指令
    public void Exec(Channel channel,String cmd)throws Exception{
        cmd=cmd+"\n";
        Write(channel,cmd.getBytes());
    }
    //读取指令执行的输出
    public String GetCommandOutput(Channel channel)throws Exception{
        byte[] data=Read(channel);
        return new String(data);
    }
    //退出连接
    public void Exit(){
        session.disconnect();
    }

}
