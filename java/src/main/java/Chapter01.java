import redis.clients.jedis.Jedis;
import redis.clients.jedis.ZParams;

import java.util.*;

public class Chapter01 {
    private static final int ONE_WEEK_IN_SECONDS = 7 * 86400;
    private static final int VOTE_SCORE = 432;
    private static final int ARTICLES_PER_PAGE = 25;

    public static final void main(String[] args) {
        new Chapter01().run();
    }

    public void run() {
        Jedis conn = new Jedis("localhost");
        conn.select(15);

        //创建一个新文章
        String articleId = postArticle(
            conn, "username", "A title", "http://www.google.com");
        //打印日志
        System.out.println("We posted a new article with id: " + articleId);
        System.out.println("Its HASH looks like:");
        Map<String,String> articleData = conn.hgetAll("article:" + articleId);
        for (Map.Entry<String,String> entry : articleData.entrySet()){
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }

        System.out.println();

        //文章投票
        articleVote(conn, "other_user", "article:" + articleId);
        
        //获取文章对象中的评分
        String votes = conn.hget("article:" + articleId, "votes");
        System.out.println("We voted for the article, it now has votes: " + votes);
        //如果评分小于等于1，抛出异常
        assert Integer.parseInt(votes) > 1;

        //根据页码获取文章信息的集合
        System.out.println("The currently highest-scoring articles are:");
        List<Map<String,String>> articles = getArticles(conn, 1);
        printArticles(articles);
        //如果评分等于0，抛出异常
        assert articles.size() >= 1;

        addGroups(conn, articleId, new String[]{"new-group"});
        System.out.println("We added the article to a new group, other articles include:");
        articles = getGroupArticles(conn, "new-group", 1);
        printArticles(articles);
        assert articles.size() >= 1;
    }

    //保存文章信息，生成文章投票用户集合，
    public String postArticle(Jedis conn, String user, String title, String link) {
        //文章id自增，返回文章id
        String articleId = String.valueOf(conn.incr("article:"));

        //保存给文章投票用户的集合，只保存一周
        String voted = "voted:" + articleId;
        conn.sadd(voted, user);
        conn.expire(voted, ONE_WEEK_IN_SECONDS);

        //保存文章信息
        long now = System.currentTimeMillis() / 1000;
        String article = "article:" + articleId;
        HashMap<String,String> articleData = new HashMap<String,String>();
        articleData.put("title", title);
        articleData.put("link", link);
        articleData.put("user", user);
        articleData.put("now", String.valueOf(now));
        articleData.put("votes", "1");
        conn.hmset(article, articleData);
        //添加到根据文章评分排序的文章有序集合
        conn.zadd("score:", now + VOTE_SCORE, article);
        //添加到根据文章发布时间排序的文章有序集合
        conn.zadd("time:", now, article);

        return articleId;
    }

    //文章投票
    public void articleVote(Jedis conn, String user, String article) {
        //如果文章时间已经超过一周，则不进行评分增加
        long cutoff = (System.currentTimeMillis() / 1000) - ONE_WEEK_IN_SECONDS;
        if (conn.zscore("time:", article) < cutoff){
            return;
        }

        //向该文章的用户投票集合中添加数据，如果该用户是首次投票，则投票数据自增
        String articleId = article.substring(article.indexOf(':') + 1);
        if (conn.sadd("voted:" + articleId, user) == 1) {
            conn.zincrby("score:", VOTE_SCORE, article);
            conn.hincrBy(article, "votes", 1l);
        }
    }


    //根据页码获取文章信息的集合
    public List<Map<String,String>> getArticles(Jedis conn, int page) {
        return getArticles(conn, page, "score:");
    }

    public List<Map<String,String>> getArticles(Jedis conn, int page, String order) {
        int start = (page - 1) * ARTICLES_PER_PAGE;
        int end = start + ARTICLES_PER_PAGE - 1;

        //获取范围内所有的文章id
        Set<String> ids = conn.zrevrange(order, start, end);
        //获取文章的信息
        List<Map<String,String>> articles = new ArrayList<Map<String,String>>();
        for (String id : ids){
            Map<String,String> articleData = conn.hgetAll(id);
            articleData.put("id", id);
            articles.add(articleData);
        }

        return articles;
    }

    public void addGroups(Jedis conn, String articleId, String[] toAdd) {
        String article = "article:" + articleId;
        for (String group : toAdd) {
            conn.sadd("group:" + group, article);
        }
    }

    public List<Map<String,String>> getGroupArticles(Jedis conn, String group, int page) {
        return getGroupArticles(conn, group, page, "score:");
    }

    public List<Map<String,String>> getGroupArticles(Jedis conn, String group, int page, String order) {
        String key = order + group;
        if (!conn.exists(key)) {
            ZParams params = new ZParams().aggregate(ZParams.Aggregate.MAX);
            conn.zinterstore(key, params, "group:" + group, order);
            conn.expire(key, 60);
        }
        return getArticles(conn, page, key);
    }

    private void printArticles(List<Map<String,String>> articles){
        for (Map<String,String> article : articles){
            System.out.println("  id: " + article.get("id"));
            for (Map.Entry<String,String> entry : article.entrySet()){
                if (entry.getKey().equals("id")){
                    continue;
                }
                System.out.println("    " + entry.getKey() + ": " + entry.getValue());
            }
        }
    }
}
