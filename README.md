# Spring Cloud Function サンプル

## Spring Cloud Function で REST API を作る
### プロジェクト作成

[Spring Initalizr](https://start.spring.io/) を使って、アプリケーションの雛形を作成する<br>

* gradle プロジェクトを採用
* Dependencies には、 `Function` を追加する。<br>
* AWS Lambda は 2023/04/12 現在 Java のランタイムとして `Coretto11` にしか対応していないため、`Java11` + `Spring Boot 2.7.10` とする

### build.gradle の修正

* dependencies に記載されている `spring-cloud-function-context` を、Spring Cloud Function 公式ドキュメントの [Standalone Web Applications の項](https://docs.spring.io/spring-cloud-function/docs/3.2.9/reference/html/spring-cloud-function.html#_standalone_web_applications) に記載されている、スタンドアロンに必要なオプションをまとめてある `spring-cloud-starter-function-webflux` と置き換える。(webfluxとしてあるのは新しいモノが好きだから)
* dependencies に `org.springframework.boot:spring-boot-starter` は不要となるので削除する

### 関数を作成

Spring Cloud Function は、デフォルトで 関数Bean または 関数クラスを探して、REST API として登録する。<br>
関数Bean の場合は、メインクラス または Configuration クラスに以下の様に記述する。

```java
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }

    @Bean
    public Function<String, String> uppercase() {
        return String::toUpperCase;
    }
}
```

関数クラスとする場合は、Functionインターフェースを持つクラスを作成する

```java
public class Uppercase implements Function<String, String> {
    @Override
    public String apply(String s) {
        return s.toUpperCase();
    }
}
```

特別なアノテーションなどはいらない。<br>
なお、関数クラスとする場合で、関数をメインクラス下位のパッケージに格納する場合は、application.yaml にスキャンするパッケージを下記のように指定する

```yaml
spring:
  cloud:
    function:
      scan:
        packages: com.example.sample.functions
```

今回は、関数クラスを作成する方を採用する。

### 最終的なパッケージ構成

```text
.
├── java
│   └── com
│       └── example
│           └── sample
│               ├── SampleApplication.java
│               └── functions
│                   └── Uppercase.java
└── resources
    └── application.yaml
```

### 動作確認

`gradle build` でビルドすると、Springアプリケーションが起動するので、下記 curl コマンドで動作確認

```shell
curl localhost:8080/uppercase -H "Content-Type: text/plain" -d "hello, spring cloud function!"
```

実行すると、`HELLO, SPRING CLOUD FUNCTION!` が返ってくる。<br>
Spring Cloud Function は、関数クラス（または関数Bean）をURLにマッピングして、REST API として動作させる。<br>
今回の場合は、関数クラス `Uppercase` が `/uppercase` にマッピングされている。 
