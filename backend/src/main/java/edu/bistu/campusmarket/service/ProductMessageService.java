package edu.bistu.campusmarket.service;

import edu.bistu.campusmarket.exception.BusinessException;
import edu.bistu.campusmarket.mapper.ProductMessageMapper;
import edu.bistu.campusmarket.mapper.ProductMessageMapper.Conversation;
import edu.bistu.campusmarket.mapper.ProductMessageMapper.Message;
import edu.bistu.campusmarket.mapper.SellerProductMapper.GeneratedId;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("db")
public class ProductMessageService {
    private static final int PAGE_SIZE = 30;
    private final ProductMessageMapper mapper;
    private final NotificationService notifications;

    public ProductMessageService(ProductMessageMapper mapper, NotificationService notifications) {
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @Transactional
    public Sent start(long buyer, long product, String content) {
        Long seller = mapper.visibleSeller(product);
        if (seller == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在或已下架");
        }
        if (seller == buyer) {
            throw new BusinessException(HttpStatus.CONFLICT, "SELF_MESSAGE", "不能给自己的商品留言");
        }
        return send(new Conversation(product, buyer, seller), buyer, content);
    }

    @Transactional(readOnly = true)
    public ThreadPage thread(long actor, long id, Long before) {
        Conversation thread = owned(actor, id);
        List<Message> page = mapper.page(thread, before, PAGE_SIZE + 1);
        List<Item> items = page.stream().limit(PAGE_SIZE)
                .map(m -> new Item(m.id(), m.senderId() == actor, m.content(), m.createdAt())).toList();
        Long nextBefore = page.size() > PAGE_SIZE ? items.get(PAGE_SIZE - 1).id() : null;
        return new ThreadPage(thread.productId(), items, nextBefore);
    }

    @Transactional
    public Sent reply(long actor, long id, String content) {
        return send(owned(actor, id), actor, content);
    }

    private Conversation owned(long actor, long id) {
        Conversation thread = mapper.owned(id, actor);
        if (thread == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_MESSAGE_NOT_FOUND", "留言不存在");
        }
        return thread;
    }

    private Sent send(Conversation thread, long actor, String content) {
        GeneratedId key = new GeneratedId();
        if (mapper.insert(thread, actor, content.strip(), key) != 1 || key.getId() == null) {
            throw new IllegalStateException("Product message was not recorded");
        }
        notifications.emitProductMessage(actor == thread.buyerId() ? thread.sellerId() : thread.buyerId(), key.getId());
        return new Sent(key.getId());
    }

    public record Sent(long messageId) {}
    public record Item(long id, boolean mine, String content, LocalDateTime createdAt) {}
    public record ThreadPage(long productId, List<Item> items, Long nextBefore) {}
}
