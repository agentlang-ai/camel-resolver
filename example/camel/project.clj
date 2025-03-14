(defproject fractl.example/camel "0.0.1"
  :dependencies [[com.github.agentlang-ai/agentlang "0.6.2"]
                 [com.github.fractl-io/camel-resolver "0.0.1"]
                 [org.apache.camel/camel-main "4.6.0"]
                 [org.apache.camel/camel-jackson "4.6.0"]
                 [org.apache.camel/camel-salesforce "4.6.0"]
                 [org.apache.camel/camel-google-sheets "4.6.0"]]
  :main fractl.example.camel.core
  :aot :all)
