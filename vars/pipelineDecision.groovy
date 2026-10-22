#!groovy

def decidePipeline(Map configMap){
    def type = configMap.get("type")

    switch(type){
        case "javaEKSCI":
                javaEKSCI(configMap)
        break

        case "nodeJSEKS":
                nodeJSEKS(configMap)
        break

        default:
            error "type is not matched"
        break
    }
}