/** 把 Blob 保存为文件（触发浏览器下载） */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 给浏览器一点时间开始下载再释放
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
