package com.wechat.ferry.handle;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.springframework.util.ObjectUtils;

import com.alibaba.fastjson2.JSONObject;
import com.sun.jna.Native;
import com.wechat.ferry.entity.dto.WxPpMsgDTO;
import com.wechat.ferry.entity.proto.Wcf.DbQuery;
import com.wechat.ferry.entity.proto.Wcf.DbRow;
import com.wechat.ferry.entity.proto.Wcf.DecPath;
import com.wechat.ferry.entity.proto.Wcf.Functions;
import com.wechat.ferry.entity.proto.Wcf.MemberMgmt;
import com.wechat.ferry.entity.proto.Wcf.Request;
import com.wechat.ferry.entity.proto.Wcf.Response;
import com.wechat.ferry.entity.proto.Wcf.Verification;
import com.wechat.ferry.entity.proto.Wcf.WxMsg;
import com.wechat.ferry.service.SDK;
import com.wechat.ferry.utils.HttpClientUtil;
import com.wechat.ferry.utils.XmlJsonConvertUtil;

import io.sisu.nng.Socket;
import io.sisu.nng.pair.Pair1Socket;
import lombok.extern.slf4j.Slf4j;

/**
 * 处理层-微信客户端
 * version：39.3.3
 *
 * @author Changhua
 * @date 2023-12-06 22:11
 */
@Slf4j
public class WeChatSocketClient {

    /**
     * 消息缓冲区大小，16M
     */
    private static final Integer BUFFER_SIZE = 16 * 1024 * 1024;

    /**
     * 默认IP
     */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * 请求地址
     */
    private static final String CMD_URL = "tcp://%s:%s";

    private Socket cmdSocket = null;
    private Socket msgSocket = null;

    /**
     * 是否收到消息
     */
    private boolean isReceivingMsg = false;

    /**
     * 是否为本地端口
     */
    private boolean isLocalHostPort = false;

    /**
     * 消息返回
     */
    private BlockingQueue<WxMsg> msgQ;

    private final String host;
    private final Integer port;

    public WeChatSocketClient(Integer port, String dllPath) {
        this(DEFAULT_HOST, port, false, dllPath);
    }

    public WeChatSocketClient(String host, Integer port, boolean debug, String dllPath) {
        this.host = host;
        this.port = port;

        SDK INSTANCE = Native.load(dllPath, SDK.class);
        int status = INSTANCE.WxInitSDK(debug, port);
        if (status != 0) {
            log.error("启动 RPC 失败: {}", status);
            System.exit(-1);
        }
        connectRPC(String.format(CMD_URL, host, port), INSTANCE);
        if (DEFAULT_HOST.equals(host) || "localhost".equalsIgnoreCase(host)) {
            isLocalHostPort = true;
        }
    }

    public void connectRPC(String url, SDK INSTANCE) {
        try {
            cmdSocket = new Pair1Socket();
            cmdSocket.dial(url);
            while (!isLogin()) {
                // 直到登录成功
                waitMs(1000);
            }
        } catch (Exception e) {
            log.error("连接 RPC 失败: ", e);
            System.exit(-1);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭...");
            diableRecvMsg();
            if (isLocalHostPort) {
                INSTANCE.WxDestroySDK();
            }
        }));
    }

    public Response sendCmd(Request req) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(req.toByteArray());
            cmdSocket.send(bb);
            ByteBuffer ret = ByteBuffer.allocate(BUFFER_SIZE);
            long size = cmdSocket.receive(ret, true);
            return Response.parseFrom(Arrays.copyOfRange(ret.array(), 0, (int)size));
        } catch (Exception e) {
            log.error("命令调用失败: ", e);
            return null;
        }
    }

    /**
     * 当前微信客户端是否登录微信号
     *
     * @return 是否登录结果
     */
    public boolean isLogin() {
        Request req = Request.newBuilder().setFuncValue(Functions.FUNC_IS_LOGIN_VALUE).build();
        Response rsp = sendCmd(req);
        if (rsp != null) {
            return rsp.getStatus() == 1;
        }
        return false;
    }

    /**
     * 获取sql执行结果
     *
     * @param db 数据库名
     * @param sql 执行的sql语句
     * @return 数据记录列表
     */
    public List<DbRow> querySql(String db, String sql) {
        DbQuery dbQuery = DbQuery.newBuilder().setSql(sql).setDb(db).build();
        Request req = Request.newBuilder().setFuncValue(Functions.FUNC_EXEC_DB_QUERY_VALUE).setQuery(dbQuery).build();
        Response rsp = sendCmd(req);
        if (rsp != null) {
            return rsp.getRows().getRowsList();
        }
        return null;
    }

    /**
     * 接收好友请求
     *
     * @param v3 xml.attrib["encryptusername"]
     * @param v4 xml.attrib["ticket"]
     * @return 结果状态码
     */
    public int acceptNewFriend(String v3, String v4) {
        int ret = -1;
        Verification verification = Verification.newBuilder().setV3(v3).setV4(v4).build();
        Request req = Request.newBuilder().setFuncValue(Functions.FUNC_ACCEPT_FRIEND_VALUE).setV(verification).build();
        Response rsp = sendCmd(req);
        if (rsp != null) {
            ret = rsp.getStatus();
        }
        return ret;
    }

    /**
     * 添加群成员为微信好友
     *
     * @param roomID 群ID
     * @param wxIds 要加群的人列表，逗号分隔
     * @return 1 为成功，其他失败
     */
    public int addChatroomMembers(String roomID, String wxIds) {
        int ret = -1;
        MemberMgmt memberMgmt = MemberMgmt.newBuilder().setRoomid(roomID).setWxids(wxIds).build();
        Request req = Request.newBuilder().setFuncValue(Functions.FUNC_ADD_ROOM_MEMBERS_VALUE).setM(memberMgmt).build();
        Response rsp = sendCmd(req);
        if (rsp != null) {
            ret = rsp.getStatus();
        }
        return ret;
    }

    /**
     * 解密图片
     *
     * @param srcPath 加密的图片路径
     * @param dstPath 解密的图片路径
     * @return 是否成功
     */
    public boolean decryptImage(String srcPath, String dstPath) {
        int ret = -1;
        DecPath build = DecPath.newBuilder().setSrc(srcPath).setDst(dstPath).build();
        Request req = Request.newBuilder().setFuncValue(Functions.FUNC_DECRYPT_IMAGE_VALUE).setDec(build).build();
        Response rsp = sendCmd(req);
        if (rsp != null) {
            ret = rsp.getStatus();
        }
        return ret == 1;
    }

    public boolean getIsReceivingMsg() {
        return isReceivingMsg;
    }

    public WxMsg getMsg() {
        try {
            return msgQ.take();
        } catch (Exception e) {
            // TODO: handle exception
            return null;
        }
    }

    /**
     * 判断是否是艾特自己的消息
     *
     * @param wxMsgXml XML消息
     * @param wxMsgContent 消息内容
     * @param selfWxId 自己的微信id
     * @return 是否
     */
    public boolean isAtMeMsg(String wxMsgXml, String wxMsgContent, String selfWxId) {
        String format = String.format("<atuserlist><![CDATA[%s]]></atuserlist>", selfWxId);
        boolean isAtAll = wxMsgContent.startsWith("@所有人") || wxMsgContent.startsWith("@all");
        if (wxMsgXml.contains(format) && !isAtAll) {
            return true;
        }
        return false;
    }

    private void listenMsg(String url) {
        try {
            msgSocket = new Pair1Socket();
            msgSocket.dial(url);
            // 设置 2 秒超时
            msgSocket.setReceiveTimeout(2000);
        } catch (Exception e) {
            log.error("创建消息 RPC 失败", e);
            return;
        }
        ByteBuffer bb = ByteBuffer.allocate(BUFFER_SIZE);
        while (isReceivingMsg) {
            try {
                long size = msgSocket.receive(bb, true);
                WxMsg wxMsg = Response.parseFrom(Arrays.copyOfRange(bb.array(), 0, (int)size)).getWxmsg();
                msgQ.put(wxMsg);
            } catch (Exception e) {
                // 多半是超时，忽略吧
            }
        }
        try {
            msgSocket.close();
        } catch (Exception e) {
            log.error("关闭连接失败", e);
        }
    }

    public void enableRecvMsg(int qSize) {
        if (isReceivingMsg) {
            return;
        }

        Request req = Request.newBuilder().setFuncValue(Functions.FUNC_ENABLE_RECV_TXT_VALUE).build();
        Response rsp = sendCmd(req);
        if (rsp == null) {
            log.error("启动消息接收失败");
            isReceivingMsg = false;
            return;
        }

        isReceivingMsg = true;
        msgQ = new ArrayBlockingQueue<>(qSize);
        String msgUrl = "tcp://" + this.host + ":" + (this.port + 1);
        Thread thread = new Thread(() -> listenMsg(msgUrl));
        thread.start();
    }

    public int diableRecvMsg() {
        if (!isReceivingMsg) {
            return 1;
        }
        int ret = -1;
        Request req = Request.newBuilder().setFuncValue(Functions.FUNC_DISABLE_RECV_TXT_VALUE).build();
        Response rsp = sendCmd(req);
        if (rsp != null) {
            ret = rsp.getStatus();
            if (ret == 0) {
                isReceivingMsg = false;
            }

        }
        return ret;
    }

    public void waitMs(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public void printWxMsg(WxMsg msg) {
        WxPpMsgDTO dto = new WxPpMsgDTO();
        dto.setIsSelf(msg.getIsSelf());
        dto.setIsGroup(msg.getIsGroup());
        dto.setId(msg.getId());
        dto.setType(msg.getType());
        dto.setTs(msg.getTs());
        dto.setRoomId(msg.getRoomid());
        dto.setContent(msg.getContent());
        dto.setSender(msg.getSender());
        dto.setSign(msg.getSign());
        dto.setThumb(msg.getThumb());
        dto.setExtra(msg.getExtra());
        dto.setXml(msg.getXml().replace("\n", "").replace("\t", ""));

        String jsonString = JSONObject.toJSONString(dto);
        log.info("收到消息: {}", jsonString);
    }

    public String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public void keepRunning() {
        while (true) {
            waitMs(1000);
        }
    }

    public void forwardMsg(WxMsg msg, String url) {
        String xml = msg.getXml();
        xml = xml.replaceAll(">[\\s\\p{Zs}]*<", "><");
        String content = msg.getContent();
        content = content.replaceAll(">[\\s\\p{Zs}]*<", "><");

        WxPpMsgDTO dto = new WxPpMsgDTO();
        dto.setIsSelf(msg.getIsSelf());
        dto.setIsGroup(msg.getIsGroup());
        dto.setId(msg.getId());
        dto.setType(msg.getType());
        dto.setTs(msg.getTs());
        dto.setRoomId(msg.getRoomid());
        dto.setSender(msg.getSender());
        dto.setSign(msg.getSign());
        dto.setThumb(msg.getThumb());
        dto.setExtra(msg.getExtra());
        dto.setXml(xml);
        // 根据消息类型判断 引用-49
        if (!ObjectUtils.isEmpty(msg.getContent()) && "49".equals("" + msg.getType())) {
            try {
                dto.setQuoteContent(content);
                JSONObject json = XmlJsonConvertUtil.xml2Json(content);
                // 获取第一层级的JSONObject
                JSONObject level1 = json.getJSONObject("msg");
                if (!ObjectUtils.isEmpty(level1)) {
                    // 获取第二层级的JSONObject
                    JSONObject level2 = level1.getJSONObject("appmsg");
                    if (!ObjectUtils.isEmpty(level2)) {
                        // 获取field字段的值
                        String fieldValue = level2.getString("title");
                        dto.setContent(fieldValue);
                    }
                }
                dto.setJsonContent(json);
            } catch (Exception e) {
                log.error("XML提取报错：", e);
                // 报错就使用原值
                dto.setContent(content);
            }
        } else {
            dto.setContent(content);
        }

        String jsonString = JSONObject.toJSONString(dto);
        try {
            String responseStr = HttpClientUtil.doPostJson(url, jsonString);
            if (!JSONObject.parseObject(responseStr).getString("code").equals("200")) {
                log.error("本机消息转发失败！-URL：{}", url);
            }
        } catch (Exception e) {
            log.error("转发接口报错：", e);
        }
    }

}
