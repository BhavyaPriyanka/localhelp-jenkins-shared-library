#!groovy

def decidePipeline(Map configMap){
    def type = configMap.get("type")

    switch(type){
        case "javaEKS-CI":
                javaEKS(configMap)
        break

        case "nodeJSEKS":
                nodeJSEKS(configMap)
        break

        default:
            error "type is not matched"
        break
    }
}