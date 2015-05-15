package com.foxinmy.weixin4j.socket;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpMethod;

import java.io.ByteArrayInputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.foxinmy.weixin4j.bean.AesToken;
import com.foxinmy.weixin4j.dispatcher.WeixinMessageDispatcher;
import com.foxinmy.weixin4j.exception.WeixinException;
import com.foxinmy.weixin4j.request.WeixinMessage;
import com.foxinmy.weixin4j.request.WeixinRequest;
import com.foxinmy.weixin4j.type.EncryptType;
import com.foxinmy.weixin4j.util.Consts;
import com.foxinmy.weixin4j.util.HttpUtil;
import com.foxinmy.weixin4j.util.MessageUtil;
import com.foxinmy.weixin4j.util.StringUtil;

/**
 * 微信请求处理类
 * 
 * @className WeixinRequestHandler
 * @author jy
 * @date 2014年11月16日
 * @since JDK 1.7
 * @see
 */
public class WeixinRequestHandler extends
		SimpleChannelInboundHandler<WeixinRequest> {
	private final Logger log = LoggerFactory.getLogger(getClass());

	private final AesToken aesToken;
	private final WeixinMessageDispatcher messageDispatcher;

	public WeixinRequestHandler(AesToken aesToken,
			WeixinMessageDispatcher messageDispatcher) throws WeixinException {
		this.aesToken = aesToken;
		this.messageDispatcher = messageDispatcher;
	}

	public void channelReadComplete(ChannelHandlerContext ctx) {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		ctx.close();
		log.error("catch the exception:{}", cause.getMessage());
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, WeixinRequest request)
			throws WeixinException {
		log.info("\n=================message request=================\n{}",
				request);
		if (request.getMethod().equals(HttpMethod.GET.name())) {
			if (MessageUtil.signature(aesToken.getToken(),
					request.getTimeStamp(), request.getNonce()).equals(
					request.getSignature())) {
				ctx.writeAndFlush(
						HttpUtil.createHttpResponse(request.getEchoStr(), OK,
								Consts.CONTENTTYPE$TEXT_PLAIN)).addListener(
						ChannelFutureListener.CLOSE);
				return;
			}
			ctx.writeAndFlush(
					HttpUtil.createHttpResponse(null, FORBIDDEN, null))
					.addListener(ChannelFutureListener.CLOSE);
			return;
		} else if (request.getMethod().equals(HttpMethod.POST.name())) {
			if (!MessageUtil.signature(aesToken.getToken(),
					request.getTimeStamp(), request.getNonce()).equals(
					request.getSignature())) {
				ctx.writeAndFlush(
						HttpUtil.createHttpResponse(null, FORBIDDEN, null))
						.addListener(ChannelFutureListener.CLOSE);
				return;
			}
			if (request.getEncryptType() == EncryptType.AES) {
				if (!MessageUtil.signature(aesToken.getToken(),
						request.getTimeStamp(), request.getNonce(),
						request.getEncryptContent()).equals(
						request.getMsgSignature())) {
					ctx.writeAndFlush(
							HttpUtil.createHttpResponse(null, FORBIDDEN, null))
							.addListener(ChannelFutureListener.CLOSE);
					return;
				}
			}
		} else {
			ctx.writeAndFlush(
					HttpUtil.createHttpResponse(null, METHOD_NOT_ALLOWED, null))
					.addListener(ChannelFutureListener.CLOSE);
			return;
		}
		final String message = request.getOriginalContent();
		WeixinMessage weixinMessage;
		try {
			Unmarshaller unmarshaller = JAXBContext.newInstance(
					WeixinMessage.class).createUnmarshaller();
			Source source = new StreamSource(new ByteArrayInputStream(
					message.getBytes()));
			JAXBElement<WeixinMessage> jaxbElement = unmarshaller.unmarshal(
					source, WeixinMessage.class);
			weixinMessage = jaxbElement.getValue();
		} catch (JAXBException e) {
			throw new WeixinException(e);
		}
		ctx.channel().attr(Consts.ENCRYPTTYPE_KEY)
				.set(request.getEncryptType());
		ctx.channel().attr(Consts.USEROPENID_KEY)
				.set(weixinMessage.getFromUserName());
		if (StringUtil.isBlank(aesToken.getAppid())) {
			ctx.channel().attr(Consts.ACCOUNTOPENID_KEY)
					.set(weixinMessage.getToUserName());
		}
		messageDispatcher.doDispatch(ctx, request, message);
	}
}
