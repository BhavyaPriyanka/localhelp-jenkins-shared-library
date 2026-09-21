#!groovy

def decidePipeline(Map configMap){
    type = configMap.get("type")

    switch(type){
        case "javaEKS":
                javaEKS(configMap)
        break

        case "javaVM":
                javaVM(configMap)
        break

        default:
            error "type is not matched"
        break

    }
}