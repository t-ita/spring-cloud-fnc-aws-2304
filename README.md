# Spring Cloud Function + AWS Lambda サンプル

## Spring Cloud Function で REST API を作る
### プロジェクト作成

[Spring Initializr](https://start.spring.io/) を使って、アプリケーションの雛形を作成する<br>

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

`gradle build` でビルドすると、 `build.libs` 以下に jar ファイルが生成される。<br>
以下のコマンドでアプリケーション起動

```shell
java -jar ./build/libs/sample-0.0.1-SNAPSHOT.jar
```

下記 curl コマンドで動作確認

```shell
curl localhost:8080/uppercase -H "Content-Type: text/plain" -d "hello, spring cloud function!"
```

実行すると、`HELLO, SPRING CLOUD FUNCTION!` が返ってくる。<br>
Spring Cloud Function は、関数クラス（または関数Bean）をURLにマッピングして、REST API として動作させる。<br>
今回の場合は、関数クラス `Uppercase` が `/uppercase` にマッピングされている。 

## Spring Cloud Function アプリケーションを、AWS Lambda 関数にする

### 依存関係を追加

AWS Lambda 対応に必要な依存関係を追加する

```groovy
ext {
    set('springCloudVersion', "2021.0.6")
    set('awsLambdaCoreVersion', '1.2.2') // 追加
    set('awsLambdaEventsVersion', '3.11.1') // 追加
}

dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-function-webflux'
    implementation 'org.springframework.cloud:spring-cloud-function-adapter-aws' // 追加
    compileOnly "com.amazonaws:aws-lambda-java-core:${awsLambdaCoreVersion}"  // 追加
    compileOnly "com.amazonaws:aws-lambda-java-events:${awsLambdaEventsVersion}"  // 追加
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

### シェーディングされた jar を作るためにプラグインを導入する

build.gradle を以下の様に編集し、Shade プラグイン , Thin プラグインを追加する

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '2.7.10'
    id 'io.spring.dependency-management' version '1.0.15.RELEASE'
    id 'com.github.johnrengelman.shadow' version '7.1.2' // 追加
    id 'org.springframework.boot.experimental.thin-launcher' version '1.0.29.RELEASE' // 追加
}
```

### shadowJar を生成するタスクを追加

aws にアップロードするための jar を生成するタスクを追加する

```groovy
import com.github.jengelman.gradle.plugins.shadow.transformers.*

shadowJar {
	archiveClassifier.set('aws')
	dependencies {
		exclude(dependency('org.springframework.cloud:spring-cloud-function-webflux'))
	}
	// Required for Spring
	mergeServiceFiles()
	append 'META-INF/spring.handlers'
	append 'META-INF/spring.schemas'
	append 'META-INF/spring.tooling'
	transform(PropertiesFileTransformer) {
		paths = ['META-INF/spring.factories']
		mergeStrategy = "append"
	}
}
```

### ビルド時に、thinJar, shadowJar を同時に作れるよう依存関係を追加

```groovy
assemble.dependsOn = [shadowJar, thinJar]
```

### AWS Lambda で実行可能にするために、マニフェストにメインクラスを指定

```groovy
jar {
	manifest {
		attributes 'Main-Class': 'com.example.sample.SampleApplication'
	}
}
```

### デプロイ

`gradle build` でビルドすると、 `build.libs` 以下に jar ファイルが2種類生成される。<br>
このうち、 `-aws.jar` が末尾になっているものが、AWS Lambda 用の jar ファイルとなる。<br>

**なお、この時点で通常の jar ファイルはアプリ起動できなくなる（要調査：thin-launcherの動作を確認）**<br>
が、今回の目的は AWS Lambda 関数として動かすことなので、いったん無視する。

上記で作成された `*-aws.jar` を、AWS Lambda 関数として登録する。<br>
コンソールで実行しても良いが、AWS CLI を利用して、下記コマンドでも生成できる

```shell
aws lambda create-function --role [実行ロールのARN] \
  --function-name SpringCloudFunctionSample \
  --zip-file fileb://build/libs/sample-0.0.1-SNAPSHOT-aws.jar \
  --handler org.springframework.cloud.function.adapter.aws.FunctionInvoker \
  --description "Spring Cloud Function Adapter Example" \
  --runtime java11 \
  --timeout 30 \
  --memory-size 2048 \
  --publish
```

**handler の指定が、`org.springframework.cloud.function.adapter.aws.FunctionInvoker` であることに注意**<br>
Spring Cloud Function （の AWS Adapter） は、上記ハンドラを起点として、アプリケーションに登録されている関数を呼び出す。<br>

### 動作確認

動作確認として、登録した関数に AWS Lambda Function URL を作成して、curl コマンドを投げる<br>
起動時間などを含めたパフォーマンスを計測するため、 `-w` オプションを付ける

```shell
curl https://*****.lambda-url.ap-northeast-1.on.aws/ -H "Content-Type: text/plain" -d "hello, spring cloud function!" \
  -w " - http_code: %{http_code}, time_total: %{time_total}\n"
```

URL の指定に、path が無いことに注意<br>
Spring Cloud Function AWS Adapter では、ひとつの関数だけが AWS Lambda 関数としてバインドされるので、path による指定はできない<br>
今回は、関数が一つだったので問題無く動作したが、関数が複数ある場合は、（path指定ではない）関数の指定が必要になる<br>
(これについてはあとでかく)

上記結果は下記のようになった。

```text
"HELLO, SPRING CLOUD FUNCTION!" - http_code: 200, time_total: 5.306902
```

AWS Lambda 関数として動作していることがわかる。<br>
トータル時間が `5秒` となっているのは、いわゆる Java のコールドスタート問題で、Spring の起動に時間がかかっているため。<br>
なお、この直後にもういちど同じコマンドを実行すると、以下の様になった

```text
"HELLO, SPRING CLOUD FUNCTION!" - http_code: 200, time_total: 0.069804
```

すでに起動しているので、実行時間は `0.07秒` となっている。<br>
しかし、AWS Lambda はずっと起動しっぱなしではないし、同時接続数が増えるとコールドスタートが発生する可能性があるので、このままでは WEBサービスの API としては使いにくい。

これに対して、これまではNativeイメージを作成して起動時間を短くすることが対策として考えられていたが、[AWS re:Invent 2022 で AWS Lambda SnapStart が発表](https://aws.amazon.com/jp/about-aws/whats-new/2022/11/aws-lambda-snapstart-java-functions/) された。<br>
SnapStart が有効か確認することにする。

## AWS Lambda SnapStart に対応する

### AWS Lambda SnapStart とは

[AWS Lambda SnapStart](https://aws.amazon.com/jp/blogs/news/new-accelerate-your-lambda-functions-with-lambda-snapstart/) は、Java で開発が進められている [CRaC](https://openjdk.org/projects/crac/) を転用したもの<br>
CRaC は、Javaのプロセスイメージをスナップショットとして取得し、再起動時にスナップショットからプロセスを復元することで、Java特有の起動の遅さを解決する技術。<br>

### AWS Lambda の設定

SnapStart を有効にするのは簡単で、コンソールでは関数の一般設定で、SnapStart の項目を `None` から `PublishedVersions` に変更すると有効になる。<br>
AWS CLI では、以下の様なコマンドになる。

```shell
aws lambda update-function-configuration --function-name SpringCloudFunctionSample \
  --snap-start ApplyOn=PublishedVersions
```

以降、バージョンを作成する度に、スナップショットが取得される。
AWS CLI では、以下の様なコマンドになる。

```shell
aws lambda publish-version --function-name SpringCloudFunctionSample
```

これでバージョンが作成される。<br>
CloudWatchでログを見ると、INIT START して Spring が起動しているログが確認できる。<br>

```text
INIT_START Runtime Version: java:11.v19	Runtime Version ARN: arn:aws:lambda:ap-northeast-1::runtime:*****
（中略）
:: Spring Boot ::               (v2.7.10)
（中略）
```

このバージョンに対して AWS Lambda Function URL を作成して、下記 curl コマンドを投げる

```shell
curl https://*****.lambda-url.ap-northeast-1.on.aws/ -H "Content-Type: text/plain" -d "hello, spring cloud function!" \
  -w " - http_code: %{http_code}, time_total: %{time_total}\n"
```

結果は以下の様になった

```text
"HELLO, SPRING CLOUD FUNCTION!" - http_code: 200, time_total: 1.452490
```

トータルタイムが `1.4秒` になっており、70% 高速化していることがわかる。<br>
なお、CloudWatch のログを確認すると、以下の様に出力されている。

```text
RESTORE_START Runtime Version: java:11.v19	Runtime Version ARN: arn:aws:lambda:ap-northeast-1::runtime:***
RESTORE_REPORT Restore Duration: 315.26 ms
```

スナップショットから `300ms` かけて復元したことが確認できる。

### ランタイムフックの設定

SnapStart によって AWS Lambda の起動が高速化されることが確認できた。<br>
しかし、SnapStart はプロセスのスナップショットをとるという仕組み上、プロセスに状態を保持すると、その状態が使い回されてしまう。<br>
例えば、以下の様な場合に考慮の必要がありそう<br>
* RDSとの接続状態
* 認証情報
* 処理で利用する一時データ
* 一意性が必要名データ
こうした場合に対処するために、ランタイムフックを利用して、復元時に実行する動作を設定出来るようになっている<br>
ランタイムフックの実装は、以下のように行う。

まず、CRaCの依存性を build.gradle 追加する

```groovy
ext {
    set('springCloudVersion', "2021.0.6")
    set('awsLambdaCoreVersion', '1.2.2')
    set('awsLambdaEventsVersion', '3.11.1')
    set('cracVersion', '0.1.3') // 追加
}

dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-function-webflux'
    implementation 'org.springframework.cloud:spring-cloud-function-adapter-aws'
    compileOnly "com.amazonaws:aws-lambda-java-core:${awsLambdaCoreVersion}"
    compileOnly "com.amazonaws:aws-lambda-java-events:${awsLambdaEventsVersion}"
    implementation "io.github.crac:org-crac:${cracVersion}" // 追加
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

実装した関数に、CRaC の `Resource` インターフェースを実装し、メソッドをオーバーライドする。<br>
また、コンストラクタで関数クラスをコンテキストに登録する<br>

```java
public class Uppercase implements Function<String, String>, Resource {
    
    private static final Logger logger = LoggerFactory.getLogger(Uppercase.class);

    public Uppercase() {
        Core.getGlobalContext().register(this); // CRaC のグローバルコンテキストに登録
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        logger.info("Before checkpoint");
        // チェックポイント作成前の操作をここに書く
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        logger.info("After restore");
        // スナップショットからの復元時の操作をここに書く
    }

    @Override
    public String apply(String s) {
        return s.toUpperCase();
    }
}
```

上記変更を行ってデプロイし、確認用の curl コマンドを投げると以下の様な結果が返ってきた

```text
"HELLO, SPRING CLOUD FUNCTION!" - http_code: 200, time_total: 1.295677
```

コンテキストに追加する処理を書いたせいか、実行時間が `1.4秒` -> `1.2秒` になっている。誤差かもしれないが。

なお、CloudWatchLogs でログを見ると、以下の様なログが出力されている

```text
2023-04-21 02:47:55.708  INFO 8 --- [           main] com.example.sample.functions.Uppercase   : After restore
```

ランタイムフックが正しく動作していることがわかる。

### 同時接続での速度確認

Java で AWS Lambda を実装する場合の問題は、同時接続が発生したときにコールドスタートが多発して速度に問題が出ることだった。<br>
そこで、[Apache Bench](https://httpd.apache.org/docs/2.4/programs/ab.html) を利用して、同時接続が発生した場合のパフォーマンスを測定する。

```shell
ab -n 100 -c 10 
```

上記コマンドで、リクエスト 100 件を、並行数 10 で行う。イメージとしては、10ユーザーが同時に10リクエストを要求している感じ。
結果は、以下の通り

```text
Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:       27   71  33.4     68     156
Processing:    23  160 304.9     57    1286
Waiting:       23  156 305.6     52    1285
Total:         53  230 308.2    127    1362

Percentage of the requests served within a certain time (ms)
  50%    127
  66%    171
  75%    183
  80%    211
  90%    916
  95%   1118
  98%   1285
  99%   1362
 100%   1362 (longest request)
```

トータルでみると、最大が 1362 ミリ秒、最小が 53 ミリ秒。
この最大値が許容できれば、REST API として利用するのもアリかもしれない。

## 関数が二つある場合の Spring Cloud Function AWS Adapter の動作

上で、「今回は、関数が一つだったので問題無く動作したが、関数が複数ある場合は、（path指定ではない）関数の指定が必要になる」と記述したが、その動作を確認する。

### 関数クラスの追加

Uppercase関数クラスに加え、文字列を逆順にする Reverse関数クラスを作成する

```java
public class Reverse implements Function<String, String> {
    @Override
    public String apply(String s) {
        return new StringBuilder(s).reverse().toString();
    }
}
```

これで、プロジェクト内に uppercase と reverse という二つの関数が存在する事になる

### デプロイ
`gradle build` でビルドし、AWS Lambda にデプロイ後、バージョン発行を行う。

### 動作確認
いままでと同様の下記コマンドを実行してみる

```shell
curl https://*****.lambda-url.ap-northeast-1.on.aws/ -H "Content-Type: text/plain" -d "hello, spring cloud function!" \
    -w " - http_code: %{http_code}, time_total: %{time_total}\n"
```

結果として、以下のテキストが返ってくる

```text
Internal Server Error - http_code: 502, time_total: 1.872654
```

CloudWatchLogs を確認すると、下記のようなログが出力されている

```text
Failed to establish route, since neither were provided: 'spring.cloud.function.definition' as Message header or as application property or 'spring.cloud.function.routing-expression' as application property. 
```

ざっくりいうと、「適切な情報が与えられなかったので、ルートを特定できませんでした」ということらしい<br>
これは、関数が二つあるために、どちらを呼び出すかわからないためだ。<br>
なので、下記のように、ヘッダーに `spring.cloud.function.definition:uppercase` を追加する

```shell
curl https://*****.lambda-url.ap-northeast-1.on.aws/ -H "Content-Type: text/plain" -d "hello, spring cloud function!" \
    -H "spring.cloud.function.definition:uppercase"  \
    -w " - http_code: %{http_code}, time_total: %{time_total}\n"
```

以下の様に結果が返る

```text
"HELLO, SPRING CLOUD FUNCTION!" - http_code: 200, time_total: 0.113230
```

正しく `uppercase` 関数クラスが呼び出されていることがわかる。<br>
先ほどのコマンドで、ヘッダに`spring.cloud.function.definition:reverse` を指定して実行してみる

```shell
curl https://*****.lambda-url.ap-northeast-1.on.aws/ -H "Content-Type: text/plain" -d "hello, spring cloud function!" \
    -H "spring.cloud.function.definition:reverse"  \
    -w " - http_code: %{http_code}, time_total: %{time_total}\n"
```

結果は、次の通り

```text
"!noitcnuf duolc gnirps ,olleh" - http_code: 200, time_total: 0.225064
```

正しく `reverse` 関数が呼ばれている。

