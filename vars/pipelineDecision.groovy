#!groovy

def decidePipeline(Map configMap) {
    def type = configMap.get("type")

    switch (type) {
        case "javaEKSCI":
            javaEKSCI(configMap)
            break

        case "nodeJSEKS":
            nodeJSEKS(configMap)
            break

        case "javaEKSCD":
            this."javaEKS-CD"(configMap)
            break

        case "nodeJSEKSCD":
            this."nodeJSEKS-CD"(configMap)
            break

        default:
            error "type is not matched: ${type}"
    }
}