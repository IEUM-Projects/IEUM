// 이음 배포 (DEPLOY-01)
// GitHub 의 main 에 올리면(push) Jenkins 가 받아서 → 빌드 → 같은 EC2 의 /opt/ieum 에 배포 → 서버 다시 켜기 → 살아났는지 확인.
// 살아나지 않으면 deploy.sh 가 바로 앞 버전으로 되돌린다.
pipeline {
    agent any

    options {
        disableConcurrentBuilds()                       // 두 번 연달아 올려도 하나씩
        buildDiscarder(logRotator(numToKeepStr: '20'))  // 빌드 기록 20개까지
    }

    parameters {
        // 프로젝트의 기본 시험(contextLoads)은 DB·설정 파일이 있어야 돌아서 기본은 건너뛴다
        booleanParam(name: 'RUN_TESTS', defaultValue: false, description: 'gradle test 도 돌릴까요?')
    }

    triggers {
        githubPush()    // GitHub Webhook (https://jenkins.ieum.me.kr/github-webhook/)
    }

    stages {
        stage('빌드') {
            steps {
                sh 'chmod +x gradlew'
                sh "./gradlew clean bootJar ${params.RUN_TESTS ? 'test' : ''} --no-daemon"
            }
        }
        stage('배포') {
            steps {
                // build/libs 의 실행 jar (plain jar 제외) 를 넘긴다
                sh '''
                    JAR=$(ls build/libs/*.jar | grep -v -- '-plain' | head -1)
                    echo "배포할 파일: $JAR"
                    sudo /opt/ieum/bin/deploy.sh "$JAR" "$BUILD_NUMBER"
                '''
            }
        }
    }

    post {
        success { echo '배포 끝: https://ieum.me.kr' }
        failure { echo '실패했어요. 위 로그와 서버의 journalctl -u ieum, /var/log/ieum/ieum.log 를 확인해 주세요.' }
    }
}
