package com.example.instagram;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@SpringBootApplication
public class InstagramBackendApplication {
    public static void main(String[] args){
        SpringApplication.run(InstagramBackendApplication.class, args);
    }
}

@RestController
@RequestMapping("/api")
class ApiController {
    private final AuthService auth = AuthService.instance();
    private final UserService users = UserService.instance();
    private final PostService posts = PostService.instance();

    @PostConstruct
    public void seed(){
        users.createUser("alice","Alice");
        users.createUser("bob","Bob");
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req){
        if(req.username == null || req.name == null) return ResponseEntity.badRequest().body(Map.of("error","invalid"));
        User u = users.createUser(req.username, req.name);
        return ResponseEntity.ok(Map.of("userId", u.id, "username", u.username));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req){
        Optional<User> ou = users.findByUsername(req.username);
        if(ou.isEmpty()) return ResponseEntity.status(401).body(Map.of("error","no_user"));
        String token = auth.createToken(ou.get().id);
        return ResponseEntity.ok(Map.of("token", token));
    }

    @PostMapping("/posts")
    public ResponseEntity<?> createPost(@RequestHeader("Authorization") String token, @RequestBody CreatePostRequest r){
        Integer uid = auth.authToken(token);
        if(uid==null) return ResponseEntity.status(401).body(Map.of("error","unauthorized"));
        if(r.caption==null && (r.mediaUrls==null || r.mediaUrls.isEmpty())) return ResponseEntity.badRequest().body(Map.of("error","empty_post"));
        Post p = posts.createPost(uid, r.caption, r.mediaUrls);
        return ResponseEntity.ok(Map.of("postId", p.id));
    }

    @GetMapping("/feed")
    public ResponseEntity<?> feed(@RequestHeader("Authorization") String token){
        Integer uid = auth.authToken(token);
        if(uid==null) return ResponseEntity.status(401).body(Map.of("error","unauthorized"));
        List<PostView> feed = posts.getFeedForUser(uid, users);
        return ResponseEntity.ok(Map.of("feed", feed));
    }

    @PostMapping("/posts/{postId}/like")
    public ResponseEntity<?> like(@RequestHeader("Authorization") String token, @PathVariable int postId){
        Integer uid = auth.authToken(token);
        if(uid==null) return ResponseEntity.status(401).body(Map.of("error","unauthorized"));
        boolean ok = posts.toggleLike(postId, uid);
        if(!ok) return ResponseEntity.status(404).body(Map.of("error","not_found"));
        return ResponseEntity.ok(Map.of("liked", posts.isLikedBy(postId, uid)));
    }

    @PostMapping("/posts/{postId}/comment")
    public ResponseEntity<?> comment(@RequestHeader("Authorization") String token, @PathVariable int postId, @RequestBody CommentRequest r){
        Integer uid = auth.authToken(token);
        if(uid==null) return ResponseEntity.status(401).body(Map.of("error","unauthorized"));
        if(r.text==null || r.text.isBlank()) return ResponseEntity.badRequest().body(Map.of("error","empty_comment"));
        Comment c = posts.addComment(postId, uid, r.text);
        if(c==null) return ResponseEntity.status(404).body(Map.of("error","not_found"));
        return ResponseEntity.ok(Map.of("commentId", c.id));
    }

    @PostMapping("/users/{userId}/follow")
    public ResponseEntity<?> follow(@RequestHeader("Authorization") String token, @PathVariable int userId){
        Integer uid = auth.authToken(token);
        if(uid==null) return ResponseEntity.status(401).body(Map.of("error","unauthorized"));
        if(users.findById(userId).isEmpty()) return ResponseEntity.status(404).body(Map.of("error","user_not_found"));
        boolean following = users.toggleFollow(uid, userId);
        return ResponseEntity.ok(Map.of("following", following));
    }
}

class RegisterRequest { public String username; public String name; }
class LoginRequest { public String username; }
class CreatePostRequest { public String caption; public List<String> mediaUrls; }
class CommentRequest { public String text; }

class UserService {
    private static final UserService S = new UserService();
    private final Map<Integer, User> byId = new ConcurrentHashMap<>();
    private final Map<String, Integer> byUsername = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> followers = new ConcurrentHashMap<>();
    private int seq = 1;
    static UserService instance(){ return S; }
    synchronized User createUser(String username, String name){
        if(byUsername.containsKey(username)) return byId.get(byUsername.get(username));
        int id = seq++;
        User u = new User(id, username, name);
        byId.put(id, u);
        byUsername.put(username, id);
        followers.put(id, ConcurrentHashMap.newKeySet());
        return u;
    }
    Optional<User> findByUsername(String username){
        Integer id = byUsername.get(username);
        return id==null?Optional.empty():Optional.of(byId.get(id));
    }
    Optional<User> findById(int id){ return Optional.ofNullable(byId.get(id)); }
    boolean toggleFollow(int byUser, int targetUser){
        if(byUser==targetUser) return false;
        Set<Integer> set = followers.get(targetUser);
        if(set==null) return false;
        if(set.contains(byUser)) { set.remove(byUser); return false; }
        else { set.add(byUser); return true; }
    }
    List<Integer> followersOf(int uid){ return new ArrayList<>(followers.getOrDefault(uid, Collections.emptySet())); }
}

class AuthService {
    private static final AuthService S = new AuthService();
    private final Map<String, Integer> tokenToUser = new ConcurrentHashMap<>();
    static AuthService instance(){ return S; }
    String createToken(int uid){
        String t = UUID.randomUUID().toString();
        tokenToUser.put(t, uid);
        return t;
    }
    Integer authToken(String header){
        if(header==null) return null;
        String t = header;
        if(header.startsWith("Bearer ")) t = header.substring(7);
        return tokenToUser.get(t);
    }
}

class PostService {
    private static final PostService S = new PostService();
    private final Map<Integer, Post> byId = new ConcurrentHashMap<>();
    private int seq = 1;
    static PostService instance(){ return S; }
    Post createPost(int authorId, String caption, List<String> media){
        int id = seq++;
        Post p = new Post(id, authorId, caption==null?"":caption, media==null?List.of():List.copyOf(media), Instant.now().toEpochMilli());
        byId.put(id, p);
        return p;
    }
    List<PostView> getFeedForUser(int uid, UserService users){
        return byId.values().stream()
                .sorted(Comparator.comparingLong((Post p)->p.createdAt).reversed())
                .map(p->toView(p, users))
                .collect(Collectors.toList());
    }
    boolean toggleLike(int postId, int uid){
        Post p = byId.get(postId);
        if(p==null) return false;
        if(p.likes.contains(uid)) p.likes.remove(uid); else p.likes.add(uid);
        return true;
    }
    boolean isLikedBy(int postId, int uid){ Post p = byId.get(postId); return p!=null && p.likes.contains(uid); }
    Comment addComment(int postId, int uid, String text){
        Post p = byId.get(postId);
        if(p==null) return null;
        int cid = p.nextCommentId++;
        Comment c = new Comment(cid, uid, text, Instant.now().toEpochMilli());
        p.comments.add(c);
        return c;
    }
    PostView toView(Post p, UserService users){
        String author = users.findById(p.authorId).map(u->u.username).orElse("unknown");
        return new PostView(p.id, p.authorId, author, p.caption, p.mediaUrls, p.likes.size(), p.comments.size(), p.createdAt);
    }
}

class User { public int id; public String username; public String name; User(int i,String u,String n){id=i;username=u;name=n;} }
class Post { public int id; public int authorId; public String caption; public List<String> mediaUrls; public long createdAt; public Set<Integer> likes = ConcurrentHashMap.newKeySet(); public List<Comment> comments = Collections.synchronizedList(new ArrayList<>()); public int nextCommentId = 1; Post(int id,int a,String c,List<String> m,long t){this.id=id;this.authorId=a;this.caption=c;this.mediaUrls=m;this.createdAt=t;} }
class Comment { public int id; public int authorId; public String text; public long createdAt; Comment(int id,int a,String t,long c){this.id=id;this.authorId=a;this.text=t;this.createdAt=c;} }
class PostView { public int id; public int authorId; public String authorUsername; public String caption; public List<String> mediaUrls; public int likeCount; public int commentCount; public long createdAt; PostView(int id,int aid,String au,String c,List<String> m,int l,int cm,long t){this.id=id;this.authorId=aid;this.authorUsername=au;this.caption=c;this.mediaUrls=m;this.likeCount=l;this.commentCou
